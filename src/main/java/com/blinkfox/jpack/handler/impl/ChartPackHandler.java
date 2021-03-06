package com.blinkfox.jpack.handler.impl;

import com.blinkfox.jpack.consts.ChartGoalEnum;
import com.blinkfox.jpack.consts.ImageBuildResultEnum;
import com.blinkfox.jpack.consts.PlatformEnum;
import com.blinkfox.jpack.entity.Docker;
import com.blinkfox.jpack.entity.HelmChart;
import com.blinkfox.jpack.entity.ImageBuildObserver;
import com.blinkfox.jpack.entity.PackInfo;
import com.blinkfox.jpack.entity.RegistryUser;
import com.blinkfox.jpack.exception.PackException;
import com.blinkfox.jpack.handler.AbstractPackHandler;
import com.blinkfox.jpack.utils.AesKit;
import com.blinkfox.jpack.utils.CmdKit;
import com.blinkfox.jpack.utils.Logger;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import okhttp3.Credentials;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.io.RawInputStreamFacade;

/**
 * ChartPackHandler.
 *
 * @author blinkfox on 2020-06-18.
 * @since v1.5.0
 */
@Getter
public class ChartPackHandler extends AbstractPackHandler {

    private static final String VERSION = "version";

    private static final String SUCCESS = "success";

    private static final String STR_TRUE = "true";

    /**
     * 进行 Helm Chart 构建的相关信息.
     */
    private HelmChart helmChart;

    /**
     * 打包后 chart 包的路径.
     */
    private String chartTgzPath;

    /**
     * 根据打包的相关参数进行打包的方法.
     *
     * @param packInfo 打包的相关参数实体
     */
    @Override
    public void pack(PackInfo packInfo) {
        super.packInfo = packInfo;
        this.helmChart = packInfo.getHelmChart();
        if (this.helmChart == null) {
            Logger.info("【Chart构建 -> 跳过】没有配置【<helmChart>】的相关内容，将跳过 HelmChart 相关的构建.");
            return;
        }

        // 判断是否有构建目标.
        final String[] goals = this.helmChart.getGoals();
        if (ArrayUtils.isEmpty(goals)) {
            Logger.info("【Chart构建 -> 跳过】没有配置【<helmChart>】的构建目标【<goals>】，将跳过 HelmChart 相关的构建.");
            return;
        }

        // 检查 helm 环境.
        if (!this.checkHelmEnv()) {
            Logger.info("【Helm 环境检查 -> 出错】没有检测到【helm】的环境变量，将跳过 Helm Chart 的相关构建."
                    + "请到这里【https://github.com/helm/helm/releases】下载最新版的 helm，并将其设置到 path 环境变量中.");
            return;
        }

        super.createPlatformCommonDir(PlatformEnum.HELM_CHART);

        // 将构建目标的字符串转换为枚举，存入到 set 集合中.
        this.doBuild(goals);
    }

    private void doBuild(String[] goals) {
        Set<ChartGoalEnum> goalSet = this.buildChartGoalEnum(goals);
        int goalSize = goalSet.size();
        if (goalSize == 1) {
            ChartGoalEnum goalEnum = ChartGoalEnum.of(goals[0]);
            if (goalEnum == null) {
                Logger.warn("【Chart构建 -> 跳过】你配置的【<helmChart.goals>】构建目标的值不是【package】、"
                        + "【push】、【save】三者中的内容，将会跳过 Helm Chart 的相关构建.");
                return;
            }

            if (goalEnum == ChartGoalEnum.PACKAGE) {
                // 如果只有一个打包目标，则只进行打包即可.
                this.packageChart();
            } else if (goalEnum == ChartGoalEnum.PUSH) {
                // 如果目标是推送，则会先打包，打包成功后再推送.
                if (this.packageChart()) {
                    this.pushChart();
                }
            } else if (goalEnum == ChartGoalEnum.SAVE && this.packageChart()) {
                // 如果目标是导出更大的包，则也会先打包，打包成功后再导出.
                this.saveChart();
            }
        } else if (goalSize == 2) {
            if (goalSet.contains(ChartGoalEnum.PACKAGE) && goalSet.contains(ChartGoalEnum.PUSH)) {
                // 如果目标是打包和推送，则会先打包，打包成功后再推送.
                if (this.packageChart()) {
                    this.pushChart();
                }
            } else if (goalSet.contains(ChartGoalEnum.PACKAGE) && goalSet.contains(ChartGoalEnum.SAVE)) {
                // 如果目标是打包和导出更大的包，则也会先打包，打包成功后再导出.
                if (this.packageChart()) {
                    this.saveChart();
                }
            } else if (goalSet.contains(ChartGoalEnum.PUSH)
                    && goalSet.contains(ChartGoalEnum.SAVE)
                    && this.packageChart()) {
                // 如果目标是推送和导出更大的包，则也会先打包，打包成功后再推送、导出.
                this.pushChart();
                this.saveChart();
            }
        } else {
            // 否则，表示打包、推送以及导出都做.
            if (this.packageChart()) {
                this.pushChart();
                this.saveChart();
            }
        }
    }

    /**
     * 构建 Chart 目标枚举类的集合.
     *
     * @param goals 目标的字符串数组
     * @return 目标的枚举 Set 集合
     */
    private Set<ChartGoalEnum> buildChartGoalEnum(String[] goals) {
        Set<ChartGoalEnum> goalEnumSet = new HashSet<>(4);
        for (String goal : goals) {
            ChartGoalEnum goalEnum = ChartGoalEnum.of(goal);
            if (goalEnum != null) {
                goalEnumSet.add(goalEnum);
            }
        }
        return goalEnumSet;
    }

    /**
     * 检查 Helm 环境是否符合构建的需求，须在操作系统的 path 变量中配置 {@code helm} 环境变量.
     */
    private boolean checkHelmEnv() {
        try {
            String result = CmdKit.execute(new String[] {"helm", VERSION});
            Logger.debug("【Helm 指令 -> 完毕】执行【helm version】命令检测 Helm 环境完成，结果为：\n" + result);
            return result.contains(VERSION);
        } catch (Exception e) {
            Logger.warn(e.getMessage());
            return false;
        }
    }

    /**
     * 打包 Chart 为 `.tgz` 格式.
     *
     * @return 布尔值结果
     */
    private boolean packageChart() {
        // 判断 helmChart 源 yaml 文件是否存在，或者是否是目录.
        File file = new File(this.helmChart.getLocation());
        if (!file.exists()) {
            Logger.info("【Chart打包 -> 放弃】Helm Chart 中的各源 yaml 文件不存在【"
                    + this.helmChart.getLocation() + "】，请检查修改【helmChart -> location】 的值.");
            return false;
        }

        if (!file.isDirectory()) {
            Logger.info("【Chart打包 -> 放弃】Helm Chart 中的【" + this.helmChart.getLocation()
                    + "】不是一个目录，请检查修改【helmChart -> location】 的值.");
            return false;
        }

        try {
            // 使用 helm 命令来打包.
            String result = CmdKit.execute(new String[] {"helm", "package", file.getAbsolutePath()});
            Logger.debug("【Helm 指令 -> 完毕】执行【helm package】命令打包完成，结果为：\n" + result);
            if (result.toLowerCase().contains(SUCCESS)) {
                Logger.info("【Chart打包 -> 成功】执行【helm package】命令打包成功.");
                File tgzFile = new File(StringUtils.substringAfterLast(result, "to:").trim());
                if (!tgzFile.exists()) {
                    throw new PackException("【Chart打包 -> 失败】未找到打包后的 tgz 文件的位置，请检查，打包的结果为：【" + result + "】.");
                }

                // 复制打包后的文件到 jpack 主目录中，便于获取或后续使用.
                this.chartTgzPath = super.packInfo.getHomeDir() + File.separator + tgzFile.getName();
                FileUtils.copyFile(tgzFile, new File(this.chartTgzPath));
                FileUtils.forceDelete(tgzFile);
                return true;
            }
        } catch (Exception e) {
            Logger.error("【Chart打包 -> 出错】执行【helm】命令打包 Chart 出错，错误原因如下：", e);
        }
        return false;
    }

    /**
     * 推送 Chart 到远程仓库中.
     */
    private void pushChart() {
        // 如果 helm 中的 RegistryUser 是空的，就从 Docker 中去读取 RegistryUser 信息.
        RegistryUser registry = this.helmChart.getRegistryUser();
        if (registry == null) {
            registry = this.packInfo.getDocker().getRegistryUser();
        }

        String chartRepoUrl = this.buildChartRepoUrl();
        if (StringUtils.isBlank(chartRepoUrl) || registry == null
                || StringUtils.isBlank(registry.getUsername()) || StringUtils.isBlank(registry.getPassword())) {
            Logger.warn("【Chart推送 -> 跳过】未配置 registryUser 或 chartRepoUrl 相关信息，将不会推送 Chart 包.");
            return;
        }

        // 拼接推送 Chart 的 CURL 命令，并执行推送的命令.
        this.doPushChart(registry, chartRepoUrl);
    }

    /**
     * 发送 HTTP 请求推送 Chart 包到远程镜像仓库中.
     *
     * @param registry 镜像仓库
     * @param chartRepoUrl Chart 包的 URL
     */
    private void doPushChart(RegistryUser registry, String chartRepoUrl) {
        Logger.info("【Chart推送 -> 开始】开始推送 Chart 包到远程 Registry 仓库中 ...");
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> chain.proceed(chain.request()
                        .newBuilder()
                        .header("Authorization", Credentials.basic(
                                AesKit.decrypt(registry.getUsername()), AesKit.decrypt(registry.getPassword())))
                        .build()))
                .build();

        File chartFile = new File(this.chartTgzPath);
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chart", chartFile.getName(), RequestBody.create(chartFile, MultipartBody.FORM))
                .build();

        try {
            Response response =
                    client.newCall(new Request.Builder().url(chartRepoUrl).post(requestBody).build()).execute();
            if (response.isSuccessful()) {
                Logger.info("【Chart推送 -> 成功】推送 Chart 包到远程 Registry 仓库中成功.");
                return;
            }

            Logger.info("【Chart推送 -> 失败】推送 Chart 包到远程 Registry 仓库失败，状态码：[" + response.code() + "].");
        } catch (Exception e) {
            Logger.error("【Chart推送 -> 出错】推送 Chart 包到远程 Registry 仓库出错，错误原因如下：", e);
        }
    }

    /**
     * 构建 Chart 仓库所在的 URL 地址，如果未配置，就使用 Docker 中的 registry 和 repo 信息来拼接.
     *
     * @return Chart 所在的仓库 URL 地址.
     */
    private String buildChartRepoUrl() {
        String chartRepoUrl = this.helmChart.getChartRepoUrl();
        if (StringUtils.isNotBlank(chartRepoUrl)) {
            return chartRepoUrl;
        }

        // 如果 Chart 为空，就尝试使用 Docker 中的 repo 名来拼接出 Chart repo 的 URL 地址.
        Docker docker = this.packInfo.getDocker();
        String registry = docker.getRegistry();
        String repo = docker.getRepo();
        return StringUtils.isNotBlank(registry) && StringUtils.isNotBlank(repo)
                ? StringUtils.join("http://", registry, "/api/chartrepo/", repo, "/charts")
                : null;
    }

    /**
     * 将 Chart 包和离线的 Docker 镜像包、copyResource 等相关资源再一起导出成一个更大的发布包.
     */
    private void saveChart() {
        // 构建导出运行 Chart 所需的镜像.
        this.saveAllDockerImages();

        // 将 chart 源文件或其他文件复制到目标文件夹中.
        File sourceChartFile = new File(chartTgzPath);
        String targetChartPath = super.platformPath + File.separator + sourceChartFile.getName();
        try {
            FileUtils.copyFile(sourceChartFile, new File(targetChartPath));
            this.handleFilesAndCompress();
        } catch (IOException e) {
            throw new PackException("【Chart导出 -> 异常】复制并压缩最终 Chart 包的相关资源出错.", e);
        }
    }

    private void saveAllDockerImages() {
        List<String> allSaveImages = this.buildAllSaveImages(this.helmChart.getSaveImages(),
                this.packInfo.getImageBuildObserver());
        if (allSaveImages.isEmpty()) {
            Logger.info("【Chart导出 -> 开始】没有能从 Docker 中导出的镜像包，将跳过离线镜像的导出环节 ...");
            return;
        }

        Logger.info("【Chart导出 -> 开始】开始从 Docker 中导出 Chart 所需的镜像包 ...，"
                + "要导出的镜像有：\n       " + allSaveImages.toString());
        try (DockerClient dockerClient = DefaultDockerClient.fromEnv().build()) {
            dockerClient.ping();
            try (InputStream imageInput = dockerClient.save(allSaveImages.toArray(new String[] {}))) {
                String saveImageFileName = this.helmChart.getSaveImageFileName();
                saveImageFileName = StringUtils.isBlank(saveImageFileName)
                        ? super.platformPath + File.separator + "images.tar"
                        : super.platformPath + File.separator + saveImageFileName;
                FileUtils.copyStreamToFile(new RawInputStreamFacade(imageInput), new File(saveImageFileName));
                Logger.info("【Chart导出 -> 成功】从 Docker 中导出镜像包【" + saveImageFileName + "】成功.");
            }
        } catch (DockerException | DockerCertificateException e) {
            Logger.error("【Chart导出 -> 放弃】未检测到或开启 Docker 环境，将跳过 Helm Chart 导出时的镜像导出环节.", e);
        } catch (IOException e) {
            Logger.error("【Chart导出 -> 失败】从 Docker 中导出镜像失败.", e);
        } catch (InterruptedException e) {
            Logger.error("【Chart导出 -> 中断】从 Docker 中导出镜像被中断.", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 构建出所有要导出的镜像信息的数组.
     *
     * @param configSaveImages 配置的需要导出的镜像名称数组
     * @param imageObserver jpack 构建的镜像观察者对象，如果 Docker 镜像还未构建完毕，save 之前将会一直阻塞.
     * @return 镜像的集合
     */
    private List<String> buildAllSaveImages(String[] configSaveImages, ImageBuildObserver imageObserver) {
        List<String> allImages = new ArrayList<>();

        // 将配置的诸多镜像添加到集合中.
        if (ArrayUtils.isNotEmpty(configSaveImages)) {
            for (String s : configSaveImages) {
                if (StringUtils.isNotBlank(s)) {
                    allImages.add(s);
                }
            }
        }

        // 如果激活了也导出使用 jpack 构建的 Docker 镜像，那么就等待镜像构建完毕，并添加到集合中.
        if (imageObserver.isEnabled()) {
            while (imageObserver.getBuildResult() == null) {
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Logger.error("【Chart导出 -> 等待】等待 Docker 镜像的构建结果时被中断了.");
                }
            }

            // 如果构建结果是成功
            if (imageObserver.getBuildResult() == ImageBuildResultEnum.SUCCESS
                    && StringUtils.isNotBlank(imageObserver.getImageTagName())) {
                allImages.add(imageObserver.getImageTagName());
            }
        }

        return allImages;
    }

    /**
     * 将需要打包的相关文件压缩成 tar.gz 格式的压缩包.
     *
     * <p>需要生成 docs 目录，复制默认的 README.md，将这些相关文件压缩成 .tar.gz 压缩包.</p>
     * <p>文件包括：name/镜像包 xxx.tgz, name/chart.tgz, docs, README.md 等.</p>
     */
    private void handleFilesAndCompress() throws IOException {
        FileUtils.forceMkdir(new File(super.platformPath + File.separator + "docs"));
        super.copyFiles("helmChart/README.md", "README.md");
        super.compress(PlatformEnum.HELM_CHART);
    }

}
