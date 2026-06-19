# fitness-admin

运动健身助手管理后台后端服务。

## 项目简介

该项目为 `fitness-admin` 后端父工程，使用 Maven 多模块管理，主要负责管理后台业务逻辑和服务组合。后端采用 Spring Boot 3、MyBatis-Plus、Sa-Token、Knife4j、MapStruct 等技术栈。

## 模块结构

- `fitness-common`：公共依赖、工具类、基础模型
- `fitness-user`：用户相关服务
- `fitness-content`：内容管理模块，包括课程、标签等
- `fitness-workout`：训练与动作管理模块
- `fitness-achievement`：用户成就与积分模块
- `fitness-community`：社区与互动模块
- `fitness-ai`：AI 推理与智能推荐模块
- `fitness-system`：系统管理模块
- `fitness-admin-app`：管理后台应用入口模块

## 运行环境

- JDK 17
- Maven 3.8 及以上
- 可选：Docker / Docker Compose（`docker/docker-compose.yml` + `docker/server/docker-compose.yml`）

## 本地构建

在 `fitness-admin` 根目录下执行：

```bash
mvn clean install
```

单独运行管理后台应用：

```bash
cd fitness-admin
mvn -pl fitness-admin-app spring-boot:run
```

## 配置说明

配置文件位于 `fitness-admin-app/src/main/resources/`：

- `application.yml`
- `application-dev.yml`
- `application-prod.yml`

请根据本地环境调整数据库、对象存储、AI 接口、Milvus 和 Sa-Token 等配置。

## Docker 支持

- `docker/docker-compose.yml`:启动 `fitness-admin` 自身;依赖 `fitness-infra` 外部网络与 `docker/server/docker-compose.yml` 中的服务互通。
- `docker/server/docker-compose.yml`:启动 `nacos` + `qdrant` 两个依赖服务,共享 `fitness-infra` 网络。
- mysql / redis / nginx 由宿主直接安装,不纳入容器编排。
- `docker/Dockerfile`:构建 `fitness-admin` 镜像。

## 代码规范

- 使用 `UTF-8` 编码
- JDK 17
- Maven 多模块父工程
- Java 源码目录：`src/main/java`
- 资源目录：`src/main/resources`

## 备注

该后端仓库主要面向后端开发与服务整合，`fitness-admin-app` 为启动入口。若你只需要后端开发，可直接在 `fitness-admin` 根目录执行 Maven 命令。
