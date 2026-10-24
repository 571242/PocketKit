import cn.xianhua.pocketkit.voice.*;
import cn.xianhua.pocketkit.voiceoverlay.EdgeDock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class VoiceSelfTest {
    static int checks;
    static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    static double crossings(float[] x,int rate){int n=0;for(int i=rate/4+1;i<x.length-rate/4;i++)if(x[i-1]<=0&&x[i]>0)n++;return n/(double)(x.length-rate/2)*rate;}
    static String source(String path)throws Exception{return Files.readString(Paths.get(path),StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception{
        check(EdgeDock.nearEdge(0,226,1080,24)==-1,"left edge");
        check(EdgeDock.nearEdge(854,226,1080,24)==1,"right edge");
        check(EdgeDock.nearEdge(300,226,1080,24)==0,"middle");
        check(EdgeDock.x(1,48,1080)==1032,"ball right");
        check(EdgeDock.x(1,48,32)==0,"small screen");
        check(EdgeDock.clamp(1500,720-48)==672,"rotation clamp");
        check(EdgeDock.clamp(-20,720)==0,"negative clamp");
        check(EdgeDock.nearest(600,226,1080)==1,"nearest right");
        check(EdgeDock.nearest(0,226,1080)==-1,"nearest left");

        int rate=24000;float[] x=new float[rate*2];for(int i=0;i<x.length;i++)x[i]=(float)(0.25*Math.sin(2*Math.PI*220*i/rate));
        VoiceProcessor dsp=new DspVoiceProcessor();
        check(dsp.outputSampleRate(rate)==rate,"DSP output rate");
        float[] plain=dsp.process(x,rate,new VoiceProcessor.Params(VoiceProcessor.Preset.ORIGINAL,0,1),()->false);
        check(plain.length==x.length,"identity length");check(Math.abs(crossings(plain,rate)-220)<2,"identity pitch");
        float[] up=dsp.process(x,rate,new VoiceProcessor.Params(VoiceProcessor.Preset.ORIGINAL,7,1),()->false);
        check(up.length==x.length,"pitch preserves duration");check(crossings(up,rate)>280,"pitch increased");
        float[] fast=dsp.process(x,rate,new VoiceProcessor.Params(VoiceProcessor.Preset.ORIGINAL,0,1.25f),()->false);
        check(fast.length==Math.round(x.length/1.25f),"speed length");check(Math.abs(crossings(fast,rate)-220)<12,"speed preserves pitch");
        for(VoiceProcessor.Preset preset:VoiceProcessor.Preset.values()){
            float[] out=dsp.process(x,rate,new VoiceProcessor.Params(preset,0,1),()->false);
            for(float value:out)if(!Float.isFinite(value)||Math.abs(value)>1.0001)throw new AssertionError("invalid sample");checks++;
        }
        boolean cancelled=false;try{dsp.process(x,rate,new VoiceProcessor.Params(VoiceProcessor.Preset.CLEAR,0,1),()->true);}catch(VoiceProcessor.CancelledException e){cancelled=true;}check(cancelled,"cancellation");

        // Built-in AI voices are gone: no engine package, no model assets, no engine hooks in the workbench.
        check(!Files.exists(Paths.get("app/src/main/java/cn/xianhua/pocketkit/voice/ai")),"AI voice package removed");
        check(!Files.exists(Paths.get("app/src/main/assets/voice-ai")),"AI voice assets removed");
        String activity=source("app/src/main/java/cn/xianhua/pocketkit/VoiceToolsActivity.java");
        check(!activity.contains("voice.ai.")&&!activity.contains("OpenVoiceProcessor"),"workbench has no AI engine hooks");

        // Old links keep resolving: 048/V01/V02 still route to the AI-free workbench.
        // The catalog/manifest edits belong to whoever removes the dependency, so assert routing, not spelling.
        String catalog=source("app/src/main/assets/selected-tools.json");
        String legacy=source("app/src/main/java/cn/xianhua/pocketkit/AiVoiceToolsActivity.java");
        check(legacy.contains("extends VoiceToolsActivity")&&!legacy.contains("OpenVoiceProcessor"),"legacy route is a plain alias");
        check(!legacy.contains("addVoiceProcessors"),"legacy route adds no engine");
        check(!activity.contains("addVoiceProcessors"),"extension hook for extra engines removed");
        check(catalog.contains("\"V01\"")&&catalog.contains("\"V02\"")&&catalog.contains("\"048\""),"legacy voice ids kept");
        check(!catalog.contains("OpenVoice"),"catalog names no AI engine");
        check(Files.exists(Paths.get("app/src/main/java/cn/xianhua/pocketkit/VoiceToolsActivity.java")),"catalog module target exists");
        check(activity.contains("\"048\".equals(id)"),"text route supported");
        check(activity.contains("processor.outputSampleRate(input.sampleRate)"),"processor output rate used");
        check(source("app/src/main/java/cn/xianhua/pocketkit/local/TtsHelper.java").contains("public synchronized void synthesizeToFile"),"system TTS still drives 048");
        check(activity.contains("new DspVoiceProcessor()"),"local DSP remains the only engine");
        check(!activity.contains("prepareEngine")&&!activity.contains("rebuildPresets")&&!activity.contains("dspControls"),"no model unpack or engine-switch UI left");
        check(activity.contains("buildInputSwitcher()")&&activity.contains("文字配音")&&activity.contains("录音变声")&&activity.contains("导入音频"),"all input modes reachable in one workbench");
        check(activity.contains("recordedAudio")&&activity.contains("\"V01\".equals(inputMode)"),"recorded source is independent of text/file decoding");
        check(activity.contains("getRecordingState() != AudioRecord.RECORDSTATE_RECORDING"),"recording checks actual running state");

        // Unpacked models from an older install are reclaimed without touching user files.
        Path privateDir=Files.createTempDirectory("pocketkit-voice");
        try{
            Path leftover=privateDir.resolve("voice-ai");Files.createDirectories(leftover);
            Files.write(leftover.resolve("tone_color.onnx"),new byte[4096]);
            Path keep=privateDir.resolve("PocketKit-voice.wav");Files.write(keep,new byte[32]);
            check(VoiceStorage.purgeLegacyModels(privateDir.toFile())==4096,"purge reports the reclaimed bytes");
            check(!Files.exists(leftover),"leftover AI models deleted");
            check(Files.exists(keep),"user recordings untouched");
            check(VoiceStorage.purgeLegacyModels(privateDir.toFile())==0,"purge is idempotent");
            check(VoiceStorage.purgeLegacyModels(privateDir.toFile())==0,"purge tolerates a missing dir");
            Path link=leftover.resolve("recording-link");
            try {
                Files.createDirectories(leftover);
                Files.createSymbolicLink(link,keep.toAbsolutePath());
                check(VoiceStorage.purgeLegacyModels(privateDir.toFile())==0,"purge skips symbolic links");
                check(Files.exists(keep),"linked recording remains untouched");
            } catch(java.io.IOException|UnsupportedOperationException unavailable) {
                System.out.println("SKIP symlink fixture: platform privilege unavailable");
            } finally { Files.deleteIfExists(link); Files.deleteIfExists(leftover); }
        }finally{
            File[] remain=privateDir.toFile().listFiles();
            if(remain!=null)for(File file:remain)file.delete();
            privateDir.toFile().delete();
        }
        System.out.println("VoiceSelfTest PASS: "+checks+" checks");
    }
}
