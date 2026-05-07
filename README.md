# AgentX - 智能对话系统平台

AgentX 是一个基于大模型 (LLM) 和多能力平台 (MCP) 的智能 Agent 构建平台。它致力于简化 Agent 的创建流程，让用户无需复杂的流程节点或拖拽操作，仅通过自然语言和工具集成即可打造个性化的智能 Agent。

### 项目地址：
1.12.207.220

## 🔗 相关链接

### 📦 子仓库
- 🛡️ **高可用网关**: [API-Premium-Gateway](https://github.com/yuikimo/API-Premium-Gateway) - 模型高可用组件
- 🌐 **MCP网关**: [mcp-gateway](https://github.com/yuikimo/mcp-gateway) - MCP服务统一管理

#### 访问服务

| 服务 | 地址 | 说明 |
|------|------|------|
| **主应用** | http://localhost:3000 | 前端界面 |
| **后端API** | http://localhost:8088 | API服务 |
| **数据库** | http://localhost:5432 | PostgreSQL（可选） |
| **RabbitMQ** | http://localhost:5672 | 消息队列（可选） |
| **RabbitMQ管理** | http://localhost:15672 | 队列管理界面（可选） |

### 👨‍💻 环境部署

```bash
# 1.复制并编辑配置，根据需要修改 .env 文件中的配置
cp .env.example .env

# 2. 克隆项目
git clone https://github.com/yuikimo/AgentX.git
cd AgentX/deploy

# 3. 启动开发环境（Linux/macOS）
./start.sh

# 4. 启动开发环境（Windows）
start.bat
```

## ⏳ 功能
 - [x] Agent 管理（创建/发布）
 - [x] LLM 上下文管理（滑动窗口，摘要算法）
 - [x] Agent 策略（MCP）
 - [x] 大模型服务商
 - [x] 用户
 - [x] 工具市场
 - [x] MCP Server Community
 - [x] MCP Gateway 
 - [x] 预先设置工具
 - [x] Agent 定时任务
 - [x] Agent OpenAPI
 - [x] 模型高可用组件
 - [x] RAG
 - [x] 计费
 - [x] Agent 监控
 - [x] 嵌入网站组件
 - [ ] Multi Agent
 - [ ] 知识图谱
 - [x] 长期记忆 
 
## ⚙️ 环境变量配置

AgentX使用`.env`配置文件进行环境变量管理，支持丰富的自定义配置：

### 📁 配置文件说明

| 配置项 | 说明 | 默认值 |
|--------|------|-------|
| **基础服务** |  |  |
| `SERVER_PORT` | 后端API端口 | `8088` |
| `DB_PASSWORD` | 数据库密码 | `agentx_pass` |
| `RABBITMQ_PASSWORD` | 消息队列密码 | `guest` |
| **安全配置** |  |  |
| `JWT_SECRET` | JWT密钥（必须修改） | 需要设置 |
| `AGENTX_ADMIN_PASSWORD` | 管理员密码 | `admin123` |
| **外部服务** |  |  |
| `EXTERNAL_DB_HOST` | 外部数据库地址 | 空（使用内置） |
| `EXTERNAL_RABBITMQ_HOST` | 外部消息队列地址 | 空（使用内置） |

**必须修改的配置项**：
- `JWT_SECRET`: 设置安全的JWT密钥（至少32字符）
- `AGENTX_ADMIN_PASSWORD`: 修改管理员密码
- `DB_PASSWORD`: 修改数据库密码

### 📝 配置分类

<details>
<summary><strong>🔐 安全配置（重要）</strong></summary>

```env
# 生产环境必须修改
JWT_SECRET=your_secure_jwt_secret_key_at_least_32_characters
AGENTX_ADMIN_PASSWORD=your_secure_admin_password
DB_PASSWORD=your_secure_db_password
RABBITMQ_PASSWORD=your_secure_mq_password
```

</details>

<details>
<summary><strong>🔗 外部服务集成</strong></summary>

```env
# 使用外部数据库
EXTERNAL_DB_HOST=your-postgres-host
DB_HOST=your-postgres-host
DB_USER=your-db-user
DB_PASSWORD=your-db-password

# 使用外部消息队列
EXTERNAL_RABBITMQ_HOST=your-rabbitmq-host
RABBITMQ_HOST=your-rabbitmq-host
RABBITMQ_USERNAME=your-mq-user
RABBITMQ_PASSWORD=your-mq-password
```

</details>

<details>
<summary><strong>☁️ 云服务配置</strong></summary>

```env
# 阿里云OSS
OSS_ENDPOINT=https://oss-cn-beijing.aliyuncs.com
OSS_ACCESS_KEY=your_access_key
OSS_SECRET_KEY=your_secret_key
OSS_BUCKET=your_bucket_name

# AWS S3
S3_SECRET_ID=your_s3_access_key
S3_SECRET_KEY=your_s3_secret_key
S3_REGION=us-east-1
S3_BUCKET_NAME=your_bucket

# AI服务
SILICONFLOW_API_KEY=your_api_key
HIGH_AVAILABILITY_ENABLED=true
HIGH_AVAILABILITY_GATEWAY_URL=http://localhost:8081
```

</details>

<details>
<summary><strong>📧 通知与认证</strong></summary>

```env
# 邮件服务
MAIL_SMTP_HOST=smtp.qq.com
MAIL_SMTP_USERNAME=your_email@qq.com
MAIL_SMTP_PASSWORD=your_email_password

# GitHub OAuth
GITHUB_CLIENT_ID=your_github_client_id
GITHUB_CLIENT_SECRET=your_github_client_secret

# 支付服务
ALIPAY_APP_ID=your_alipay_app_id
STRIPE_SECRET_KEY=your_stripe_secret_key
```

</details>

> 📋 **完整配置参考**：查看 [.env.example](/.env.example) 文件了解所有可配置参数
