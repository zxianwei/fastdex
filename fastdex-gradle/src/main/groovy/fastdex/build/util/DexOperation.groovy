package fastdex.build.util

import com.android.build.api.transform.Transform
import fastdex.build.variant.FastdexVariant
import fastdex.common.utils.FileUtils
import org.apache.tools.ant.taskdefs.condition.Os
import org.objectweb.asm.*
import com.android.ide.common.blame.Message
import com.android.ide.common.blame.ParsingProcessOutputHandler
import com.android.ide.common.blame.parser.DexParser
import com.android.ide.common.blame.parser.ToolOutputParser
import com.android.ide.common.process.ProcessOutputHandler

/**
 * dex操作
 * Created by tong on 17/11/4.
 */
public class DexOperation implements Opcodes {
    /**
     * 生成补丁dex
     * @param fastdexVariant
     * @param base
     * @param patchJar
     * @param patchDex
     */
    public static final void generatePatchDex(FastdexVariant fastdexVariant, Transform base,File patchJar,File patchDex) {
        FileUtils.deleteFile(patchDex)
        ProcessOutputHandler outputHandler = new ParsingProcessOutputHandler(
                new ToolOutputParser(new DexParser(), Message.Kind.ERROR, base.logger),
                new ToolOutputParser(new DexParser(), base.logger),
                base.androidBuilder.getErrorReporter())
        final List<File> inputFiles = new ArrayList<>()
        inputFiles.add(patchJar)

        FileUtils.ensumeDir(patchDex.parentFile)
        String androidGradlePluginVersion = GradleUtils.getAndroidGradlePluginVersion()
        long start = System.currentTimeMillis()

        if (Os.isFamily(Os.FAMILY_WINDOWS) || fastdexVariant.project.projectDir.absolutePath.contains(" ")) {
            if ("2.0.0".equals(androidGradlePluginVersion)) {
                base.androidBuilder.convertByteCode(
                        inputFiles,
                        patchDex.parentFile,
                        false,
                        null,
                        base.dexOptions,
                        null,
                        false,
                        true,
                        outputHandler,
                        false)
            }
            else if ("2.1.0".equals(androidGradlePluginVersion) || "2.1.2".equals(androidGradlePluginVersion) || "2.1.3".equals(androidGradlePluginVersion)) {
                base.androidBuilder.convertByteCode(
                        inputFiles,
                        patchDex.parentFile,
                        false,
                        null,
                        base.dexOptions,
                        null,
                        false,
                        true,
                        outputHandler)
            }
            else if (androidGradlePluginVersion.startsWith("2.2.")) {
                base.androidBuilder.convertByteCode(
                        inputFiles,
                        patchDex.parentFile,
                        false,
                        null,
                        base.dexOptions,
                        base.getOptimize(),
                        outputHandler);
            }
            else if ("2.3.0".equals(androidGradlePluginVersion)) {
                base.androidBuilder.convertByteCode(
                        inputFiles,
                        patchDex.parentFile,
                        false,
                        null,//fix-issue#27  fix-issue#22
                        base.dexOptions,
                        outputHandler)
            }
            else {
                //TODO 补丁的方法数也有可能超过65535个，最好加上使dx生成多个dex的参数，但是一般补丁不会那么大所以暂时不处理
                //调用dx命令
                def process = new ProcessBuilder(FastdexUtils.getDxCmdPath(fastdexVariant.project),"--dex","--output=${patchDex}",patchJar.absolutePath).start()
                InputStream is = process.getInputStream()
                BufferedReader reader = new BufferedReader(new InputStreamReader(is))
                String line = null
                while ((line = reader.readLine()) != null) {
                    println(line)
                }
                reader.close()

                int status = process.waitFor()

                reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
                reader.close();
                try {
                    process.destroy()
                } catch (Throwable e) {

                }
                if (status != 0) {
                    //拼接生成dex的命令 project.android.getSdkDirectory()
                    String dxcmd = "sh ${FastdexUtils.getDxCmdPath(fastdexVariant.project)} --dex --output=${patchDex} ${patchJar}"
                    throw new FastdexRuntimeException("==fastdex generate dex fail: \n${dxcmd}")
                }
            }
        }
        else {
            File dxJarFile = new File(FastdexUtils.getBuildDir(fastdexVariant.project),"fastdex-dx.jar")
            File dxCommandFile = new File(FastdexUtils.getBuildDir(fastdexVariant.project),"fastdex-dx")

            if (!FileUtils.isLegalFile(dxJarFile)) {
                FileUtils.copyResourceUsingStream("fastdex-dx.jar",dxJarFile)
            }

            if (!FileUtils.isLegalFile(dxCommandFile)) {
                FileUtils.copyResourceUsingStream("fastdex-dx",dxCommandFile)
            }

            String dxcmd = "sh ${dxCommandFile.absolutePath} --dex --no-optimize --force-jumbo --output=${patchDex} ${patchJar}"
            if (fastdexVariant.configuration.debug) {
                fastdexVariant.project.logger.error("==fastdex exec dx: \n" + dxcmd)
            }

            //调用dx命令
            def process = new ProcessBuilder("sh",dxCommandFile.absolutePath,"--dex","--no-optimize","--force-jumbo","--output=${patchDex}",patchJar.absolutePath).start()

            InputStream is = process.getInputStream()
            BufferedReader reader = new BufferedReader(new InputStreamReader(is))
            String line = null
            while ((line = reader.readLine()) != null) {
                println(line)
            }
            reader.close()

            int status = process.waitFor()

            reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
            reader.close();
            try {
                process.destroy()
            } catch (Throwable e) {

            }

            if (status != 0) {
                throw new FastdexRuntimeException("==fastdex generate dex fail: \n${dxcmd}")
            }
        }

        long end = System.currentTimeMillis();
        fastdexVariant.project.logger.error("==fastdex patch transform generate dex success: \n==${patchDex} use: ${end - start}ms")
    }

    /**
     * 合并dex
     * @param fastdexVariant
     * @param outputDex     输出的dex路径
     * @param patchDex      补丁dex路径
     * @param cachedDex
     */
    public static void mergeDex(FastdexVariant fastdexVariant,File outputDex,File patchDex,File cachedDex) {
        long start = System.currentTimeMillis()
        def project = fastdexVariant.project
        File dexMergeCommandJarFile = new File(FastdexUtils.getBuildDir(project),Constants.DEX_MERGE_JAR_FILENAME)
        if (!FileUtils.isLegalFile(dexMergeCommandJarFile)) {
            FileUtils.copyResourceUsingStream(Constants.DEX_MERGE_JAR_FILENAME,dexMergeCommandJarFile)
        }

        String javaCmdPath = FastdexUtils.getJavaCmdPath()
        def process = new ProcessBuilder(javaCmdPath,"-jar",dexMergeCommandJarFile.absolutePath,outputDex.absolutePath,patchDex.absolutePath,cachedDex.absolutePath).start()
        int status = process.waitFor()
        try {
            process.destroy()
        } catch (Throwable e) {

        }
        if (status != 0) {
            String cmd = "${javaCmdPath} -jar ${dexMergeCommandJarFile} ${outputDex} ${patchDex} ${cachedDex}"
            throw new RuntimeException("==fastdex merge dex fail: \n${cmd}")
        }
        long end = System.currentTimeMillis();
        project.logger.error("==fastdex merge dex success: \n==${outputDex} use: ${end - start}ms")
    }
}
