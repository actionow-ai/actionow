package com.actionow.agent.skill;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 校验 {@code backend/scripts/skill-package/*.md} 与
 * {@code docker/init-db/03-agent-seed.sql} 中嵌入的 {@code $SKILL$ ... $SKILL$} heredoc
 * 内容字面对齐。
 *
 * <p>历史背景：每次新增/调整一个 skill 都要双轨同步（.md 文件 + SQL heredoc），人工容易漏改
 * 一边，造成开发环境（读 .md）与 docker init-db（读 SQL）行为不一致。本测试在 CI 拦截漂移。
 *
 * <p>校验规则：每份 .md 去掉 YAML frontmatter（首尾两条 {@code ---}）后的正文，必须与 SQL
 * 中同名 skill 的 heredoc body trim 后完全相等。任何字符不匹配立即 fail，并打印第一处差异
 * 上下文。
 */
class SkillMdSyncTest {

    /** SQL INSERT 行匹配：('skill-foo-expert', 'foo_expert', '...' */
    private static final Pattern SKILL_INSERT_PATTERN = Pattern.compile(
            "\\('(skill-[a-z0-9-]+)',\\s*'([a-z0-9_]+)',");

    /** Heredoc 体：紧随 INSERT 行之后的第一段 $SKILL$ ... $SKILL$ 区块。 */
    private static final Pattern HEREDOC_PATTERN = Pattern.compile(
            "\\$SKILL\\$\\s*\\n([\\s\\S]*?)\\n\\s*\\$SKILL\\$");

    /** YAML frontmatter（.md 头部 ---...---）。 */
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "\\A---\\s*\\n[\\s\\S]*?\\n---\\s*\\n");

    @Test
    void mdFilesMatchSqlHeredocs() throws IOException {
        Path repoRoot = findRepoRoot();
        Path sqlPath = repoRoot.resolve("docker/init-db/03-agent-seed.sql");
        Path skillDir = repoRoot.resolve("backend/scripts/skill-package");

        assertNotNull(sqlPath);
        if (!Files.exists(sqlPath)) {
            fail("找不到 SQL 文件: " + sqlPath);
        }
        if (!Files.isDirectory(skillDir)) {
            fail("找不到 skill-package 目录: " + skillDir);
        }

        Map<String, String> sqlBodies = parseSqlHeredocs(sqlPath);
        Map<String, String> mdBodies = parseMdFiles(skillDir);

        // 双向集合检查
        for (String name : sqlBodies.keySet()) {
            if (!mdBodies.containsKey(name)) {
                fail("SQL 中存在 skill '" + name + "' 但 skill-package 没有对应 .md（应同步新增）");
            }
        }
        for (String name : mdBodies.keySet()) {
            if (!sqlBodies.containsKey(name)) {
                fail(".md 中存在 skill '" + name + "' 但 SQL 没有对应 heredoc（应同步新增）");
            }
        }

        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> e : mdBodies.entrySet()) {
            String name = e.getKey();
            String md = e.getValue();
            String sql = sqlBodies.get(name);
            if (!md.equals(sql)) {
                mismatches.add(name + " — " + describeFirstDiff(md, sql));
            }
        }

        if (!mismatches.isEmpty()) {
            fail("以下 skill .md 与 SQL heredoc 已漂移，请同步两边：\n  - "
                    + String.join("\n  - ", mismatches));
        }
    }

    private Map<String, String> parseSqlHeredocs(Path sqlPath) throws IOException {
        String content = Files.readString(sqlPath, StandardCharsets.UTF_8);
        Map<String, String> result = new HashMap<>();

        Matcher inserts = SKILL_INSERT_PATTERN.matcher(content);
        while (inserts.find()) {
            String skillName = inserts.group(2); // e.g. episode_expert
            int searchFrom = inserts.end();
            Matcher hd = HEREDOC_PATTERN.matcher(content);
            if (hd.find(searchFrom)) {
                String body = hd.group(1).trim();
                result.put(skillName, body);
            }
        }
        return result;
    }

    private Map<String, String> parseMdFiles(Path skillDir) throws IOException {
        Map<String, String> result = new HashMap<>();
        try (Stream<Path> files = Files.list(skillDir)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".md"))::iterator) {
                String name = file.getFileName().toString().replaceFirst("\\.md$", "");
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                String body = FRONTMATTER_PATTERN.matcher(raw).replaceFirst("").trim();
                result.put(name, body);
            }
        }
        return result;
    }

    private String describeFirstDiff(String md, String sql) {
        int len = Math.min(md.length(), sql.length());
        for (int i = 0; i < len; i++) {
            if (md.charAt(i) != sql.charAt(i)) {
                return "首处差异 @ offset " + i + "\n      .md: " + snippet(md, i)
                        + "\n      sql: " + snippet(sql, i);
            }
        }
        if (md.length() != sql.length()) {
            return "长度不一致：md=" + md.length() + " sql=" + sql.length()
                    + "（一方多出尾部内容）";
        }
        return "未知差异";
    }

    private String snippet(String s, int offset) {
        int from = Math.max(0, offset - 20);
        int to = Math.min(s.length(), offset + 30);
        return s.substring(from, to).replace("\n", "\\n");
    }

    private Path findRepoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.exists(p.resolve("docker/init-db/03-agent-seed.sql"))
                    && Files.exists(p.resolve("backend/scripts/skill-package"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("找不到 repo 根目录（含 docker/init-db 和 backend/scripts/skill-package）");
    }
}
