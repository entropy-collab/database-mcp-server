-- 调用者身份种子数据：10 个演示身份
--
-- ============================================================================
-- 这些身份的口令全部是同一个公开值：demo-password-change-me
-- 也就是说，任何读到这个仓库的人都能用它们登录。
-- 只能用于本地开发、演示与冒烟测试。
-- ============================================================================
--
-- 为什么口令是公开的、而且十个人共用一个：
--   种子数据的用途是"起来就能点"，不是"安全"。给每个人生成一个不同的随机口令，
--   结果是十个同样躺在仓库里的秘密，安全性没有任何提升，却让人误以为它们是真凭据。
--   一个显眼的公开口令反而不会被误当成生产配置。
--
-- 为什么用户名都带 demo- 前缀：
--   前缀是数据自带的标记，SeedUserGuard 据此在 production profile 下拒绝启动
--   （见该类注释）。把名单写死在 Java 里会变成两处真相，改了 SQL 忘了改代码就失效。
--
-- 怎么用（服务已经起着、状态库是文件 H2 时）：
--   java -cp h2-*.jar org.h2.tools.RunScript \
--     -url 'jdbc:h2:file:/var/lib/mcp/state' -user sa \
--     -script database-mcp-app/src/main/resources/db/users-seed.sql
--
--   或者在 H2 Console 里贴进去执行。服务不会自动跑这个脚本：自动执行的种子数据
--   就是一个装成"默认配置"的后门。
--
-- 想改口令：
--   htpasswd -nbBC 10 x '你的口令' | cut -d: -f2-
--   把输出替换下面的哈希。$2a$ / $2b$ / $2y$ 三种前缀都能被校验。
--
-- 真正上线时请把这些删掉：
--   DELETE FROM mcp_user WHERE username LIKE 'demo-%';
--   或者只停用：UPDATE mcp_user SET enabled = 0 WHERE username LIKE 'demo-%';

-- 表由 JdbcUserStore 在启动时建好，这里只插数据。
-- MERGE 而不是 INSERT：脚本要能重复执行，而 INSERT 第二次会撞主键。
-- MERGE 是 H2 / Oracle / SQL Server 的写法；PostgreSQL 请把每行改成
-- INSERT ... ON CONFLICT (username) DO NOTHING，MySQL 用 INSERT IGNORE。

MERGE INTO mcp_user (username, principal_type, password_hash, enabled) KEY (username) VALUES
    -- 人：不同职责，用来试不同的权限组合
    ('demo-dba',      'user',    '$2y$10$yFGxah2ohTNalVPNhv/2s.MyI1f/HahbUJUNAtHxNq3bmp7CWbbnG', 1),
    ('demo-analyst',  'user',    '$2y$10$yFGxah2ohTNalVPNhv/2s.MyI1f/HahbUJUNAtHxNq3bmp7CWbbnG', 1),
    ('demo-readonly', 'user',    '$2y$10$yFGxah2ohTNalVPNhv/2s.MyI1f/HahbUJUNAtHxNq3bmp7CWbbnG', 1),
    ('demo-auditor',  'user',    '$2y$10$yFGxah2ohTNalVPNhv/2s.MyI1f/HahbUJUNAtHxNq3bmp7CWbbnG', 1),
    -- 已停用的一个：用来验证"停用后立刻登不上"，以及审计里旧记录仍对得上主体
    ('demo-leaver',   'user',    '$2y$10$yFGxah2ohTNalVPNhv/2s.MyI1f/HahbUJUNAtHxNq3bmp7CWbbnG', 0),

    -- AI agent：MCP 的调用方经常不是人，类型区分之后要进授权判定
    ('demo-claude',   'agent',   '$2y$10$yFGxah2ohTNalVPNhv/2s.MyI1f/HahbUJUNAtHxNq3bmp7CWbbnG', 1),
    ('demo-copilot',  'agent',   '$2y$10$yFGxah2ohTNalVPNhv/2s.MyI1f/HahbUJUNAtHxNq3bmp7CWbbnG', 1),

    -- 机器：定时任务与流水线
    ('demo-etl',      'service', '$2y$10$yFGxah2ohTNalVPNhv/2s.MyI1f/HahbUJUNAtHxNq3bmp7CWbbnG', 1),
    ('demo-backup',   'service', '$2y$10$yFGxah2ohTNalVPNhv/2s.MyI1f/HahbUJUNAtHxNq3bmp7CWbbnG', 1),
    ('demo-monitor',  'service', '$2y$10$yFGxah2ohTNalVPNhv/2s.MyI1f/HahbUJUNAtHxNq3bmp7CWbbnG', 1);

-- 确认：应当是 10 行，其中 demo-leaver 的 enabled = 0
-- SELECT username, principal_type, enabled FROM mcp_user WHERE username LIKE 'demo-%' ORDER BY username;
