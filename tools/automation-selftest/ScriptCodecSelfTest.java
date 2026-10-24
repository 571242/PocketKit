package tools.automation_selftest;

import cn.xianhua.pocketkit.automation.ScriptCodec;
import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses the existing desktop org.json implementation; no Android framework stubs at runtime. */
public final class ScriptCodecSelfTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable call){try{call.run();throw new AssertionError("expected invalid JSON rejection");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception {
        Script source=new Script();source.name="录制 · 测试";source.width=1080;source.height=1920;
        Step swipe=new Step(Step.Type.SWIPE);swipe.x=20;swipe.y=30;swipe.x2=300;swipe.y2=500;swipe.durationMs=850;
        swipe.gesturePath=new int[]{20,30,100,120,240,95,300,500};source.steps.add(swipe);
        String encoded=ScriptCodec.encode(source);Script result=ScriptCodec.decode(encoded);
        require(result.name.equals(source.name)&&Arrays.equals(result.steps.get(0).gesturePath,swipe.gesturePath),"recording path survives JSON roundtrip");
        require(result.steps.get(0).durationMs==850,"gesture duration roundtrip");
        Script draft=source.copy();draft.steps.add(new Step(Step.Type.WAIT_IMAGE));
        String draftText=ScriptCodec.encodeDraft(draft);require(ScriptCodec.decodeDraft(draftText).steps.size()==2,"incomplete advanced action stays editable");
        rejects(()->ScriptCodec.decode(draftText));
        JSONObject legacy=new JSONObject(encoded);legacy.getJSONArray("steps").getJSONObject(0).remove("gesturePath");
        require(ScriptCodec.decode(legacy.toString()).steps.get(0).gesturePath.length==0,"legacy v1 scripts still load");
        Script tapScript=new Script();tapScript.steps.add(new Step(Step.Type.TAP));
        require(!ScriptCodec.encode(tapScript).contains("gesturePath"),"ordinary scripts stay compatible with older exports");
        for(Object invalid:new Object[]{"wrong",JSONObject.NULL,new JSONArray("[1,2,3]"),new JSONArray("[1,2,3.5,4]"),new JSONArray("[1,2,-3,4]"),new JSONArray("[1,2,1080,4]")}){
            JSONObject bad=new JSONObject(encoded);bad.getJSONArray("steps").getJSONObject(0).put("gesturePath",invalid);
            rejects(()->ScriptCodec.decode(bad.toString()));rejects(()->ScriptCodec.decodeDraft(bad.toString()));
        }
        JSONObject wrongType=new JSONObject(encoded);wrongType.getJSONArray("steps").getJSONObject(0).put("type","TAP");rejects(()->ScriptCodec.decode(wrongType.toString()));
        JSONObject tooMany=new JSONObject(encoded);JSONArray path=new JSONArray();for(int i=0;i<Step.MAX_GESTURE_PATH_VALUES+2;i++)path.put(1);tooMany.getJSONArray("steps").getJSONObject(0).put("gesturePath",path);rejects(()->ScriptCodec.decode(tooMany.toString()));
        Script pending=new Script();Step unpicked=new Step(Step.Type.TAP);unpicked.pointPending=true;pending.steps.add(unpicked);
        String pendingDraft=ScriptCodec.encodeDraft(pending);require(ScriptCodec.decodeDraft(pendingDraft).steps.get(0).pointPending,"unpicked state survives process recreation");
        rejects(pending::validate);rejects(()->ScriptCodec.decode(pendingDraft));
        repeatRoundtrip(encoded,source);
        System.out.println("ScriptCodecSelfTest PASS: recorded path/duration roundtrip, repeat/clickMatch contract, legacy import/export, recoverable drafts, strict malformed/path-bound rejection");
    }
    private static void repeatRoundtrip(String plainEncoded,Script plainSource){
        Script repeat=new Script();repeat.repeatCount=0;repeat.repeatIntervalMs=1250;
        Step recognizing=new Step(Step.Type.WAIT_TEXT);recognizing.text="go";recognizing.clickMatch=true;
        Step branch=new Step(Step.Type.IF_IMAGE);branch.templateId="t";branch.clickMatch=true;branch.children.add(new Step(Step.Type.HOME));
        repeat.steps.add(recognizing);repeat.steps.add(branch);
        Script copy=repeat.copy();
        if(copy.repeatCount!=0||copy.repeatIntervalMs!=1250||!copy.steps.get(0).clickMatch||!copy.steps.get(1).clickMatch)
            throw new AssertionError("copy() dropped the repeat/clickMatch contract");
        String text=ScriptCodec.encode(repeat);Script back=ScriptCodec.decode(text);
        require(back.repeatCount==0&&back.repeatIntervalMs==1250,"repeat fields survive JSON roundtrip");
        require(back.steps.get(0).clickMatch&&back.steps.get(1).clickMatch,"clickMatch survives JSON roundtrip");
        require(back.steps.get(1).children.get(0).type==Step.Type.HOME,"clickMatch nesting roundtrip");
        Script defaults=new Script();defaults.steps.add(new Step(Step.Type.TAP));
        String defaultText=ScriptCodec.encode(defaults);
        require(!defaultText.contains("repeatCount")&&!defaultText.contains("repeatIntervalMs")&&!defaultText.contains("clickMatch"),"defaults stay absent so older builds can still import the export");
        Script legacySource=ScriptCodec.decode(plainEncoded);
        require(legacySource.repeatCount==1&&legacySource.repeatIntervalMs==500&&!legacySource.steps.get(0).clickMatch,"legacy v1 scripts default to one round without clickMatch");
        JSONObject legacyRoot=new JSONObject(plainEncoded);legacyRoot.remove("repeatCount");legacyRoot.remove("repeatIntervalMs");
        Script oldImport=ScriptCodec.decode(legacyRoot.toString());require(oldImport.repeatCount==1&&oldImport.repeatIntervalMs==500,"missing repeat keys fall back to one round");
        rejects(()->ScriptCodec.decode(new JSONObject(plainEncoded).put("repeatCount","three").toString()));
        rejects(()->ScriptCodec.decode(new JSONObject(plainEncoded).put("repeatCount",-1).toString()));
        rejects(()->ScriptCodec.decode(new JSONObject(plainEncoded).put("repeatIntervalMs",-1).toString()));
        rejects(()->ScriptCodec.decode(new JSONObject(plainEncoded).put("repeatIntervalMs",8L*24*3600_000L).toString()));
        rejects(()->ScriptCodec.decodeDraft(new JSONObject(plainEncoded).put("repeatCount",-2).toString()));
        rejects(()->ScriptCodec.decode(new JSONObject(plainEncoded).put("repeatCount",0).put("steps",new JSONArray()).toString()));
        rejects(()->ScriptCodec.decode(new JSONObject(plainEncoded).put("steps",new JSONArray().put(new JSONObject().put("id","a").put("type","WAIT").put("clickMatch","yes"))).toString()));
        rejects(()->ScriptCodec.decode(new JSONObject(plainEncoded).put("steps",new JSONArray().put(new JSONObject().put("id","a").put("type","TAP").put("clickMatch",true))).toString()));
        JSONObject draftRoot=new JSONObject(plainEncoded);draftRoot.put("draft",true);draftRoot.put("repeatCount",0);
        require(ScriptCodec.decodeDraft(draftRoot.toString()).repeatCount==0,"drafts keep an unfinished repeat count");
        require(plainSource.repeatCount==1&&plainSource.repeatIntervalMs==500,"the untouched source keeps the documented defaults");
    }
}
