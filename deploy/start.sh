#!/bin/bash

# AgentX本地开发环境启动脚本
# 专用于开发者进行本地开发和调试

set -e

# 启动参数
# 默认: auto（仅在本地镜像缺失时构建）
# 可选: --build（强制重建）/ --no-build（强制不构建）
BUILD_MODE="auto"
BUILD_SCOPE="auto"
for arg in "$@"; do
    case "$arg" in
        --build)
            BUILD_MODE="build"
            ;;
        --no-build)
            BUILD_MODE="no-build"
            ;;
        --build-app)
            BUILD_MODE="build"
            BUILD_SCOPE="app"
            ;;
        --build-postgres)
            BUILD_MODE="build"
            BUILD_SCOPE="postgres"
            ;;
    esac
done

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # 无颜色

# 项目信息
echo -e "${BLUE}"
echo "   ▄▄▄        ▄████  ▓█████  ███▄    █ ▄▄▄█████▓▒██   ██▒"
echo "  ▒████▄     ██▒ ▀█▒ ▓█   ▀  ██ ▀█   █ ▓  ██▒ ▓▒▒▒ █ █ ▒░"
echo "  ▒██  ▀█▄  ▒██░▄▄▄░ ▒███   ▓██  ▀█ ██▒▒ ▓██░ ▒░░░  █   ░"
echo "  ░██▄▄▄▄██ ░▓█  ██▓ ▒▓█  ▄ ▓██▒  ▐▌██▒░ ▓██▓ ░  ░ █ █ ▒ "
echo "   ▓█   ▓██▒░▒▓███▀▒ ░▒████▒▒██░   ▓██░  ▒██▒ ░ ▒██▒ ▒██▒"
echo -e "   ▒▒   ▓▒█░ ░▒   ▒  ░░ ▒░ ░░ ▒░   ▒ ▒   ▒ ░░   ▒▒ ░ ░▓ ░ ${NC}"
echo -e "${GREEN}            智能AI助手平台 - 开发环境启动工具${NC}"
echo -e "${BLUE}========================================================${NC}"
echo

# 检查Docker环境
check_docker() {
    if ! command -v docker &> /dev/null; then
        echo -e "${RED}错误: Docker未安装，请先安装Docker${NC}"
        exit 1
    fi

    if ! docker compose version &> /dev/null; then
        echo -e "${RED}错误: Docker Compose未安装或版本过低${NC}"
        exit 1
    fi
}

# 设置开发模式配置
set_development_mode() {
    MODE="dev"
    ENV_FILE=".env.local.example"
    
    echo -e "${GREEN}🔥 启动开发模式${NC}"
    echo "  - 内置数据库 + 消息队列"
    echo "  - 代码热重载支持"
    echo "  - 数据库管理工具 (Adminer)"
    echo "  - 调试端口开放"
    echo
}

# 准备环境配置
prepare_env() {
    if [ ! -f ".env" ]; then
        echo -e "${YELLOW}创建环境配置文件...${NC}"
        cp "$ENV_FILE" ".env"
        echo -e "${GREEN}✅ 已创建 .env 文件，基于模板: $ENV_FILE${NC}"
        
    else
        echo -e "${GREEN}✅ 使用现有 .env 配置文件${NC}"
    fi
}

# 启动服务
start_services() {
    echo -e "${BLUE}启动AgentX服务...${NC}"
    echo "部署模式: $MODE"
    echo

    # 设置开发环境的Docker Compose后缀
    export DOCKERFILE_SUFFIX=".dev"
    export DOCKER_BUILDKIT=1
    export COMPOSE_DOCKER_CLI_BUILD=1

    # 解析postgres镜像名（优先.env，其次默认值）
    local postgres_image="${POSTGRES_IMAGE:-}"
    if [ -z "$postgres_image" ] && [ -f ".env" ]; then
        postgres_image=$(grep -E '^[[:space:]]*POSTGRES_IMAGE=' .env | tail -n 1 | cut -d '=' -f 2- | tr -d '\r')
    fi
    if [ -z "$postgres_image" ]; then
        postgres_image="agentx-postgres-jieba:local"
    fi

    local backend_image="agentx-agentx-backend:latest"
    local frontend_image="agentx-agentx-frontend:latest"

    local need_build=false
    local missing_services=()
    if [ "$BUILD_MODE" = "build" ]; then
        need_build=true
    elif [ "$BUILD_MODE" = "no-build" ]; then
        need_build=false
    else
        if ! docker image inspect "$postgres_image" >/dev/null 2>&1; then
            need_build=true
            missing_services+=("postgres")
        fi
        if ! docker image inspect "$backend_image" >/dev/null 2>&1; then
            need_build=true
            missing_services+=("agentx-backend")
        fi
        if ! docker image inspect "$frontend_image" >/dev/null 2>&1; then
            need_build=true
            missing_services+=("agentx-frontend")
        fi
    fi

    if [ "$need_build" = true ]; then
        if [ "$BUILD_MODE" = "build" ]; then
            if [ "$BUILD_SCOPE" = "app" ]; then
                echo -e "${YELLOW}你指定了 --build-app，仅重建后端和前端镜像...${NC}"
                docker compose build --pull=false agentx-backend agentx-frontend
            elif [ "$BUILD_SCOPE" = "postgres" ]; then
                echo -e "${YELLOW}你指定了 --build-postgres，仅重建postgres镜像...${NC}"
                docker compose build --pull=false postgres
            else
                echo -e "${YELLOW}你指定了 --build，开始重建镜像（首次会较慢）...${NC}"
                docker compose build --pull=false postgres agentx-backend agentx-frontend
            fi
        else
            echo -e "${YELLOW}检测到缺少镜像：${missing_services[*]}，仅构建缺失服务...${NC}"
            docker compose build --pull=false "${missing_services[@]}"
        fi
    else
        echo -e "${GREEN}检测到本地镜像已存在，跳过构建，直接启动容器${NC}"
    fi

    # 启动开发环境服务 (使用local和dev profile)
    docker compose --profile local --profile dev up -d --no-build

    echo
    echo -e "${GREEN}🎉 AgentX启动完成！${NC}"
    echo
    echo -e "${BLUE}服务访问地址:${NC}"
    echo "  前端: http://localhost:3000"
    echo "  后端API: http://localhost:8080"
    echo "  API网关: http://localhost:8081"
    
    if [ "$MODE" = "dev" ]; then
        echo "  数据库管理: http://localhost:8082"
    fi
    
    echo
    echo -e "${BLUE}默认登录账号:${NC}"
    echo "  管理员: admin@agentx.ai / admin123"
    
    if [ "$MODE" = "local" ] || [ "$MODE" = "dev" ]; then
        echo "  测试用户: test@agentx.ai / test123"
    fi
    
    echo
    echo -e "${YELLOW}常用命令:${NC}"
    echo "  查看日志: docker compose logs -f"
    echo "  停止服务: docker compose down"
    echo "  重启服务: docker compose restart"
    echo "  查看状态: docker compose ps"
    echo "  强制重建: ./start.sh --build"
    echo "  仅重建应用: ./start.sh --build-app"
    echo "  仅重建数据库: ./start.sh --build-postgres"
}

# 主程序
main() {
    check_docker
    
    echo -e "${YELLOW}AgentX 开发环境启动${NC}"
    echo "本脚本适用于开发者进行本地开发"
    echo "如需生产环境部署，请参考: docs/deployment/PRODUCTION_DEPLOY.md"
    echo
    
    set_development_mode
    prepare_env
    start_services
}

# 运行主程序
main "$@"
