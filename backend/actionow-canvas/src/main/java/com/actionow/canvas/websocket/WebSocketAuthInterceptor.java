package com.actionow.canvas.websocket;

import com.actionow.canvas.dto.feign.TokenValidateRequest;
import com.actionow.canvas.dto.feign.TokenValidateResponse;
import com.actionow.canvas.feign.UserFeignClient;
import com.actionow.common.core.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 认证拦截器
 * 在握手阶段验证用户身份
 *
 * @author Actionow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final UserFeignClient userFeignClient;

    /**
     * WebSocket会话属性键
     */
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_NICKNAME = "nickname";
    public static final String ATTR_WORKSPACE_ID = "workspaceId";
    public static final String ATTR_CANVAS_ID = "canvasId";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        log.debug("WebSocket handshake started: {}", request.getURI());

        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpRequest = servletRequest.getServletRequest();

            // 从路径中提取 canvasId (优先)
            String path = httpRequest.getRequestURI();
            String canvasId = extractCanvasIdFromPath(path);

            // 如果路径中没有，尝试从 query parameter 提取
            if (canvasId == null) {
                canvasId = httpRequest.getParameter("canvasId");
            }

            if (canvasId != null) {
                attributes.put(ATTR_CANVAS_ID, canvasId);
            }

            // 提取 Token
            String token = extractToken(request);
            if (token == null || token.isBlank()) {
                log.warn("WebSocket handshake failed: No token provided");
                return false;
            }

            try {
                // 调用用户服务验证 Token
                TokenValidateRequest tokenRequest = new TokenValidateRequest(token);
                Result<TokenValidateResponse> result = userFeignClient.validateToken(tokenRequest);

                if (result == null || !result.isSuccess() || result.getData() == null) {
                    log.warn("WebSocket handshake failed: Token validation service error");
                    return false;
                }

                TokenValidateResponse tokenInfo = result.getData();
                if (!tokenInfo.isValid()) {
                    log.warn("WebSocket handshake failed: {}", tokenInfo.getErrorMessage());
                    return false;
                }

                // 将用户信息存入 WebSocket 会话属性
                attributes.put(ATTR_USER_ID, tokenInfo.getUserId());
                attributes.put(ATTR_USERNAME, tokenInfo.getUsername());
                attributes.put(ATTR_NICKNAME, tokenInfo.getNickname());

                // 从请求参数中获取 workspaceId（不信任请求头，网关已剥离）
                String workspaceId = httpRequest.getParameter("workspaceId");
                if (workspaceId != null) {
                    attributes.put(ATTR_WORKSPACE_ID, workspaceId);
                }

                log.info("WebSocket handshake succeeded: canvasId={}, userId={}, nickname={}",
                        canvasId, tokenInfo.getUserId(), tokenInfo.getNickname());
                return true;

            } catch (Exception e) {
                log.error("WebSocket handshake failed: Token validation error", e);
                return false;
            }
        }

        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("WebSocket 握手失败", exception);
        }
    }

    /**
     * 从路径 /ws/canvas/{canvasId} 中提取 canvasId
     */
    private String extractCanvasIdFromPath(String path) {
        String[] parts = path.split("/");
        if (parts.length >= 4 && "canvas".equals(parts[2])) {
            return parts[3];
        }
        return null;
    }

    /**
     * 从请求中提取 Token
     * 支持多种方式：
     * 1. 查询参数 ?token=xxx
     * 2. 查询参数 ?access_token=xxx
     * 3. Authorization 请求头
     * 4. Sec-WebSocket-Protocol 自定义协议头
     */
    private String extractToken(ServerHttpRequest request) {
        String query = request.getURI().getQuery();

        if (query != null) {
            // 从查询参数中提取 token
            for (String param : query.split("&")) {
                String[] keyValue = param.split("=");
                if (keyValue.length == 2) {
                    String key = keyValue[0];
                    String value = keyValue[1];
                    if ("token".equals(key) || "access_token".equals(key)) {
                        return value;
                    }
                }
            }
        }

        // 尝试从请求头中获取
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String authHeader = servletRequest.getServletRequest().getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                return authHeader.substring(7);
            }

            // 自定义协议头（用于 WebSocket 升级请求）
            String secWebSocketProtocol = servletRequest.getServletRequest()
                    .getHeader("Sec-WebSocket-Protocol");
            if (secWebSocketProtocol != null && secWebSocketProtocol.startsWith("Bearer.")) {
                return secWebSocketProtocol.substring(7);
            }
        }

        return null;
    }
}
