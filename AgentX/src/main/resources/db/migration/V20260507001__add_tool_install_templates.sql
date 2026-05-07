-- V2 MCP安装配置：公开模板与用户私有变量分离

ALTER TABLE tool_versions ADD COLUMN IF NOT EXISTS install_template JSONB;
ALTER TABLE tool_versions ADD COLUMN IF NOT EXISTS install_fields JSONB;

COMMENT ON COLUMN tool_versions.install_template IS '公开安装模板，不包含发布者私有参数';
COMMENT ON COLUMN tool_versions.install_fields IS '用户可配置字段定义(JSON)';

ALTER TABLE user_tools ADD COLUMN IF NOT EXISTS install_values TEXT;
ALTER TABLE user_tools ADD COLUMN IF NOT EXISTS config_status VARCHAR(20) NOT NULL DEFAULT 'UNCONFIGURED';

COMMENT ON COLUMN user_tools.install_values IS '用户私有安装变量值，加密JSON';
COMMENT ON COLUMN user_tools.config_status IS '安装配置状态：UNCONFIGURED/CONFIGURED/INVALID';
