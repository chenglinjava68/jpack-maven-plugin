package com.blinkfox.jpack.consts;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;

/**
 * 异常信息标识和提示信息的对应关系枚举类.
 *
 * @author blinkfox on 2019-05-13.
 * @since v1.1.0
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum ExceptionEnum {

    /**
     * 没有 Docker 环境的枚举实例.
     */
    NO_DOCKER("【构建镜像 -> 放弃】未检测到或开启 Docker 环境，将跳过 Docker 平台的镜像构建环节."),

    /**
     * 没有 Dockerfile 文件的枚举实例.
     */
    NO_DOCKERFILE("【构建镜像 -> 失败】Dockerfile 文件未找到！"),

    /**
     * 复制 jar 包异常.
     */
    COPY_JAR_TO_TARGET_EXCEPTION("【构建镜像 -> 失败】复制 jar 包到 jpack docker 目录下的 target 目录中失败！"),

    /**
     * 使用 jpack 构建 Docker 镜像出错的枚举实例.
     */
    DOCKER_BUILD_EXCEPTION("【构建镜像 -> 失败】jpack 构建 Docker 镜像失败！"),

    /**
     * 使用 jpack 导出 Docker 镜像出错的枚举实例.
     */
    DOCKER_SAVE_EXCEPTION("【导出镜像 -> 失败】jpack 导出 Docker 镜像失败！"),

    /**
     * 使用 jpack 对镜像打标签出错的枚举实例.
     */
    DOCKER_TAG_EXCEPTION("【镜像标签 -> 失败】jpack 对镜像进行打标签失败！"),

    /**
     * 使用 jpack 推送 Docker 镜像出错的枚举实例.
     */
    DOCKER_PUSH_EXCEPTION("【推送镜像 -> 失败】jpack 推送 Docker 镜像失败！");

    /**
     * 异常的描述信息.
     */
    private final String msg;

    /**
     * 获取异常的描述信息值.
     *
     * @return 描述信息
     */
    public String getMsg() {
        return this.msg;
    }

}
