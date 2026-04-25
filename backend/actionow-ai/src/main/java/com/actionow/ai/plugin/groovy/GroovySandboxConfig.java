package com.actionow.ai.plugin.groovy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Groovy沙箱安全配置
 * 定义允许和禁止的类/包
 *
 * @author Actionow
 */
@Data
@Component
@ConfigurationProperties(prefix = "actionow.ai.groovy.sandbox")
public class GroovySandboxConfig {

    /**
     * 允许导入的包/类（白名单）
     */
    private Set<String> allowedImports = Set.of(
            "java.util.*",
            "java.time.*",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.math.RoundingMode",
            "java.text.DecimalFormat",
            "java.text.SimpleDateFormat",
            "groovy.json.JsonSlurper",
            "groovy.json.JsonOutput",
            "groovy.xml.XmlSlurper"
    );

    /**
     * 允许使用的Binding类（这些类通过绑定变量注入到脚本中）
     * 脚本可以调用这些类的公开方法
     */
    private Set<String> allowedBindingClasses = Set.of(
            // 基础工具绑定
            "com.actionow.ai.plugin.groovy.binding.JsonBinding",
            "com.actionow.ai.plugin.groovy.binding.HttpBinding",
            "com.actionow.ai.plugin.groovy.binding.CryptoBinding",
            "com.actionow.ai.plugin.groovy.binding.LogBinding",
            // 扩展工具绑定
            "com.actionow.ai.plugin.groovy.binding.OssBinding",
            "com.actionow.ai.plugin.groovy.binding.DbBinding",
            "com.actionow.ai.plugin.groovy.binding.NotifyBinding",
            // 资产处理绑定
            "com.actionow.ai.plugin.groovy.binding.AssetBinding",
            // LLM 调用绑定
            "com.actionow.ai.plugin.groovy.binding.LlmBinding",
            // Provider 脚本辅助类（构建请求体 / 解析响应）
            "com.actionow.ai.plugin.groovy.binding.RequestHelper",
            "com.actionow.ai.plugin.groovy.binding.ResponseHelper"
    );

    /**
     * 安全的接收者类白名单
     * 当 useWhitelistMode = true 时，只有这些类的实例方法可以被调用
     * 这提供了比黑名单更严格的安全保证
     */
    private Set<String> allowedReceiverClasses = Set.of(
            // 基础类型
            "java.lang.String",
            "java.lang.StringBuilder",
            "java.lang.StringBuffer",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Character",
            "java.lang.Number",
            // 数学
            "java.math.BigDecimal",
            "java.math.BigInteger",
            // 集合
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.Arrays",
            "java.util.Collections",
            // 时间
            "java.time.LocalDate",
            "java.time.LocalTime",
            "java.time.LocalDateTime",
            "java.time.ZonedDateTime",
            "java.time.Instant",
            "java.time.Duration",
            "java.time.Period",
            "java.time.format.DateTimeFormatter",
            "java.util.Date",
            // 正则
            "java.util.regex.Pattern",
            "java.util.regex.Matcher",
            // JSON
            "groovy.json.JsonSlurper",
            "groovy.json.JsonOutput",
            // Optional
            "java.util.Optional",
            // UUID
            "java.util.UUID",
            // 集合遍历产物（each/collect/find 等闭包回调中常见）
            "java.util.LinkedHashMap$Entry",
            "java.util.HashMap$Node",
            "java.util.HashMap$TreeNode",
            "java.util.TreeMap$Entry",
            "java.util.AbstractMap$SimpleEntry",
            "java.util.AbstractMap$SimpleImmutableEntry",
            "java.util.Collections$UnmodifiableMap$UnmodifiableEntrySet$UnmodifiableEntry",
            "java.util.ImmutableCollections$MapN",
            "java.util.ImmutableCollections$ListN",
            "java.util.ImmutableCollections$SetN",
            "java.util.ImmutableCollections$Map1",
            "java.util.ImmutableCollections$List12",
            "java.util.ImmutableCollections$Set12",
            // Stream API（某些脚本会用 list.stream().filter { ... }）
            "java.util.stream.ReferencePipeline",
            "java.util.stream.ReferencePipeline$Head",
            "java.util.stream.ReferencePipeline$StatelessOp",
            "java.util.stream.ReferencePipeline$StatefulOp",
            // Groovy 运行时类型
            "groovy.lang.IntRange",
            "groovy.lang.ObjectRange",
            "groovy.lang.EmptyRange",
            "groovy.lang.NumberRange",
            "groovy.lang.Tuple",
            "groovy.lang.Tuple2",
            "groovy.lang.Tuple3",
            "groovy.lang.Tuple4",
            // Groovy 语言层运行时辅助类（运算符派发：==~、=~、<=>、as 等都走这里）
            // 方法名/类名黑名单仍会先生效，没有逃逸面
            "org.codehaus.groovy.runtime.*",
            // 数组字面量（[1,2,3] as int[] 等）
            "[Ljava.lang.Object;",
            "[Ljava.lang.String;",
            "[I", "[J", "[D", "[F", "[Z", "[B", "[C", "[S"
    );

    /**
     * 是否使用白名单模式
     * 启用后，只有 allowedReceiverClasses 和 allowedBindingClasses 中的类可以调用方法
     * 默认关闭，使用黑名单模式（更宽松但维护成本低）
     *
     * 白名单模式提供更强的安全保证，但可能需要根据脚本需求扩展白名单
     */
    private boolean useWhitelistMode = true;

    /**
     * 禁止使用的类（黑名单）
     */
    private Set<String> blockedClasses = Set.of(
            // 系统调用
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.System",
            "java.lang.Thread",
            "java.lang.ThreadGroup",
            "java.lang.SecurityManager",
            // 文件IO
            "java.io.File",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.io.RandomAccessFile",
            "java.io.PrintWriter",
            "java.io.BufferedWriter",
            "java.nio.file.Files",
            "java.nio.file.Paths",
            "java.nio.file.Path",
            "java.nio.channels.FileChannel",
            // 网络（原生）
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.URL",
            "java.net.URLConnection",
            "java.net.HttpURLConnection",
            "java.net.DatagramSocket",
            "java.net.MulticastSocket",
            // 反射和动态调用
            "java.lang.Class",
            "java.lang.reflect.Method",
            "java.lang.reflect.Field",
            "java.lang.reflect.Constructor",
            "java.lang.reflect.Proxy",
            "java.lang.invoke.MethodHandle",
            "java.lang.invoke.MethodHandles",
            "java.lang.invoke.MethodType",
            "java.lang.invoke.VarHandle",
            "java.lang.invoke.LambdaMetafactory",
            // 数据库
            "java.sql.Connection",
            "java.sql.DriverManager",
            "java.sql.Statement",
            "java.sql.PreparedStatement",
            "groovy.sql.Sql",
            // 类加载
            "java.lang.ClassLoader",
            "java.net.URLClassLoader",
            "groovy.lang.GroovyClassLoader",
            "groovy.lang.GroovyShell",
            // 脚本执行
            "javax.script.ScriptEngine",
            "javax.script.ScriptEngineManager",
            // 序列化（防止反序列化攻击）
            "java.io.ObjectInputStream",
            "java.io.ObjectOutputStream",
            // JNDI（防止JNDI注入）
            "javax.naming.InitialContext",
            "javax.naming.Context",
            // JMX
            "javax.management.MBeanServer",
            "java.lang.management.ManagementFactory",
            // Unsafe
            "sun.misc.Unsafe",
            "jdk.internal.misc.Unsafe",
            // 编译器和插桩
            "java.lang.Compiler",
            "java.lang.instrument.Instrumentation"
    );

    /**
     * 禁止调用的方法（黑名单）
     *
     * 注意：某些看似危险的方法名（如 "start"）被有意排除，因为它们会阻断合法调用：
     * - "start" 会阻断 String.startsWith(), StringBuilder.start() 等
     * - 对于 ProcessBuilder.start()，通过 blockedClasses 阻断 ProcessBuilder 类本身
     */
    private Set<String> blockedMethods = Set.of(
            // 进程执行（通过 blockedClasses 阻断 Runtime/ProcessBuilder）
            "execute",
            "exec",
            // "start" 被移除：会误伤 String.startsWith()，改用类级别阻断
            // 系统退出
            "exit",
            "halt",
            // 动态加载
            "load",
            "loadLibrary",
            "loadClass",
            // 反射相关
            "getClass",
            "getRuntime",
            "forName",
            "newInstance",
            "getClassLoader",
            "defineClass",
            "getMethod",
            "getMethods",
            "getDeclaredMethod",
            "getDeclaredMethods",
            "getField",
            "getFields",
            "getDeclaredField",
            "getDeclaredFields",
            "getConstructor",
            "getConstructors",
            "getDeclaredConstructor",
            "invoke",
            "setAccessible",
            // 线程操作
            "sleep",
            "wait",
            "notify",
            "notifyAll",
            "interrupt",
            // 危险的Groovy方法
            "evaluate",
            "parse",
            "parseClass"
    );

    /**
     * 最大执行时间（毫秒）
     * 注意：实际执行超时由 AiRuntimeConfigService.getGroovyMaxExecutionTimeMs() 控制（默认 300s，
     * 满足大视频上传场景）。此字段仅作为 Spring 配置绑定的备用值，优先使用 RuntimeConfig。
     */
    private long maxExecutionTimeMs = 300000;

    /**
     * 最大内存限制（MB）
     */
    private int maxMemoryMb = 64;

    /**
     * 最大递归深度
     */
    private int maxStackDepth = 50;

    /**
     * 最大循环次数
     */
    private int maxLoopIterations = 10000;

    /**
     * 是否启用沙箱
     * 默认启用，提供纵深防御
     *
     * 警告：禁用沙箱将允许脚本执行任意代码，包括：
     * - 访问文件系统
     * - 执行系统命令
     * - 建立网络连接
     * - 加载任意类
     *
     * 仅在完全可信的脚本来源和严格控制的环境下才应禁用。
     * 生产环境强烈建议保持启用状态。
     */
    private boolean enabled = true;

    /**
     * 检查类是否被禁止
     */
    public boolean isClassBlocked(String className) {
        if (className == null) {
            return false;
        }
        for (String blocked : blockedClasses) {
            if (className.equals(blocked) || className.startsWith(blocked + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查方法是否被禁止
     */
    public boolean isMethodBlocked(String methodName) {
        return methodName != null && blockedMethods.contains(methodName);
    }

    /**
     * 检查导入是否允许
     */
    public boolean isImportAllowed(String importStatement) {
        if (importStatement == null) {
            return false;
        }
        for (String allowed : allowedImports) {
            if (allowed.endsWith(".*")) {
                String prefix = allowed.substring(0, allowed.length() - 1);
                if (importStatement.startsWith(prefix)) {
                    return true;
                }
            } else if (importStatement.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查类是否是允许的Binding类
     */
    public boolean isAllowedBindingClass(String className) {
        return className != null && allowedBindingClasses.contains(className);
    }

    /**
     * 检查是否是Binding类的方法调用（允许）
     *
     * @param receiverClass 接收者类名
     * @return 是否允许调用
     */
    public boolean isBindingMethodAllowed(String receiverClass) {
        if (receiverClass == null) {
            return false;
        }
        // 检查是否在允许的Binding类列表中
        return allowedBindingClasses.contains(receiverClass);
    }

    /**
     * 检查接收者类是否在白名单中（仅白名单模式下使用）
     *
     * @param receiverClass 接收者类名
     * @return 是否允许调用该类的方法
     */
    public boolean isReceiverClassAllowed(String receiverClass) {
        if (receiverClass == null) {
            return false;
        }
        // Binding 类始终允许
        if (allowedBindingClasses.contains(receiverClass)) {
            return true;
        }
        // 精确匹配 + 包通配（".*" 后缀）匹配
        for (String allowed : allowedReceiverClasses) {
            if (allowed.endsWith(".*")) {
                String prefix = allowed.substring(0, allowed.length() - 1);
                if (receiverClass.startsWith(prefix)) {
                    return true;
                }
            } else if (allowed.equals(receiverClass)) {
                return true;
            }
        }
        // 放行 Throwable 子类的只读方法（脚本普遍使用 catch (Exception e) { e.getMessage() }），
        // 类型多变（RuntimeException / IllegalArgumentException / ConnectException / MissingPropertyException ...），
        // 逐一枚举不现实；onMethodCall 已先做方法名黑名单与类名黑名单检查。
        try {
            return Throwable.class.isAssignableFrom(Class.forName(receiverClass));
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * 综合检查方法调用是否安全
     * 根据当前模式（白名单/黑名单）进行不同的检查
     *
     * @param receiverClass 接收者类名
     * @param methodName    方法名
     * @return 是否允许调用
     */
    public boolean isMethodCallAllowed(String receiverClass, String methodName) {
        // 方法名黑名单检查（两种模式都适用）
        if (isMethodBlocked(methodName)) {
            return false;
        }

        // 类黑名单检查（两种模式都适用）
        if (isClassBlocked(receiverClass)) {
            return false;
        }

        // 白名单模式下，还需要检查接收者类是否在白名单中
        if (useWhitelistMode) {
            return isReceiverClassAllowed(receiverClass);
        }

        // 黑名单模式下，只要不在黑名单中就允许
        return true;
    }
}
