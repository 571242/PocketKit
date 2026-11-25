import cn.xianhua.pocketkit.core.automation.AdSkipRules;
import cn.xianhua.pocketkit.core.automation.AdSkipSession;

public final class AdSkipRulesSelfTest {
    private static int checks;
    public static void main(String[] args){labels();generic();known();geometry();session();System.out.println("AdSkipRulesSelfTest PASS: "+checks+" checks");}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void labels(){
        for(String s:new String[]{"跳过"," 跳过 ","跳过广告","跳过 5s","5秒 | 跳过","跳过（3秒）","Skip","SKIP 5s","Skip Ad","5s Skip","跳\u200b过","跳过此广告","跳过开屏广告","3s|跳过","点击跳过","跳过 >","✕ 跳过","关闭广告 4s"})check(AdSkipRules.isStrictSkipLabel(s),"accept "+s);
        for(String s:new String[]{null,"","关闭","×","3s","5","99","不跳过","已跳过","自动跳过","跳过片头","跳过片尾","跳过教程","跳过引导","跳过这首歌","跳过验证","点击跳过登录","搜索跳过广告","Skip song","skip payment","跳过登录","跳过设置"})check(!AdSkipRules.isStrictSkipLabel(s),"reject "+s);
        for(String s:new String[]{"跳过广告","跳过 5s","5秒 | 跳过","Skip Ad","关闭广告"})check(AdSkipRules.isExplicitAdSkipLabel(s),"explicit ad skip "+s);
        for(String s:new String[]{"跳过","Skip","点击跳过"})check(!AdSkipRules.isExplicitAdSkipLabel(s),"bare skip is not explicit "+s);
        for(String s:new String[]{"5","5s","12秒","3"})check(AdSkipRules.isCountdownLabel(s),"countdown "+s);
        for(String s:new String[]{"5.5","五分钟","一","1000",""})check(!AdSkipRules.isCountdownLabel(s),"no countdown "+s);
    }
    private static int s(String pkg,String act,String id,String text,int l,int t,int r,int b,boolean w){return AdSkipRules.score(pkg,act,id,text,null,l,t,r,b,1080,2400,w);}
    private static void generic(){
        // Explicit countdown/ad skip scores 90; bare "跳过" in right corner scores 70; bare number scores 0 (no random badge taps).
        check(s("com.example.video","com.example.video.SplashActivity",null,"跳过 3",870,60,1040,150,true)==90,"generic top-right countdown skip");
        check(s("com.any.reader","ui.Launch",null,"Skip Ad",900,2150,1050,2260,true)==90,"generic bottom-right english");
        check(s("com.any.app","Main",null,"跳过",870,60,1040,150,true)==70,"generic top-right bare skip within window");
        check(s("com.any.app","Main","com.any.app:id/whatever","5",870,70,1030,140,true)==0,"bare number badge never clicked");
        check(s("com.any.app","Main","com.any.app:id/tv_skip","5",870,70,1030,140,true)==75,"generic skip id with countdown");
        check(s("com.any.app","Main",null,"跳过",540,1100,600,1180,true)==0,"never the middle of a page");
        check(s("com.any.app","Main",null,"跳过 5s",120,60,300,140,true)==90,"top-left allowed for explicit countdown skip");
        check(s("com.any.app","Main",null,"跳过",120,60,300,140,true)==0,"top-left bare skip rejected to protect back/cancel");
        check(s("com.any.app","Main",null,"跳过",600,1200,1000,1320,true)==0,"central dialog rejected");
        check(s("com.any.app","Main",null,"跳过",870,60,1040,150,false)==0,"stale bare skip outside window");
        check(s("com.any.app","com.any.SearchActivity",null,"跳过",870,60,1040,150,true)==0,"search page excluded");
        check(s("com.any.app","com.any.LoginActivity",null,"跳过",870,60,1040,150,true)==0,"login page excluded");
        check(s("com.any.app","com.any.PaymentActivity",null,"跳过",870,60,1040,150,true)==0,"payment page excluded");
        check(s("com.example.wallet","Main",null,"跳过",870,60,1040,150,true)==0,"wallet package excluded");
        check(!AdSkipRules.isAllowedPackage("cn.xianhua.pocketkit","cn.xianhua.pocketkit"),"own package rejected by package gate");
    }
    private static void known(){
        check(s(AdSkipRules.BILI,"tv.danmaku.bili.MainActivityV2","tv.danmaku.bili:id/count_down","5",870,60,1040,150,true)==100,"observed bili ID");
        check(s(AdSkipRules.BILI,"tv.danmaku.bili.MainActivityV2","tv.danmaku.bili:id/skip","",870,60,1040,150,false)==100,"known ID survives hot splash");
        check(s("com.any.app","Main","com.any.app:id/tt_splash_skip_btn","",870,60,1040,150,false)==100,"Pangle SDK splash skip ID");
        check(s("com.any.app","Main","com.any.app:id/ksad_splash_circle_skip_view","",870,60,1040,150,false)==100,"Kwai SDK splash skip ID");
        check(s("com.any.app","Main","com.any.app:id/splash_skip_view","",870,60,1040,150,false)==100,"generic splash_skip ID");
        int foreign=s("com.other.app","Main","tv.danmaku.bili:id/count_down","5",870,60,1040,150,true);
        check(foreign==0,"a foreign package ID and bare number never scores: "+foreign);
        check(AdSkipRules.knownId("com.other.app","tv.danmaku.bili:id/count_down")==false,"known ID is package bound");
        check(s(AdSkipRules.MUSIC,"com.netease.cloudmusic.activity.LoadingActivity",null,"跳过 5",870,60,1040,150,true)==90,"music countdown");
        check(s(AdSkipRules.MUSIC,"com.netease.cloudmusic.music.biz.search.activity.SearchActivity",null,"跳过",870,60,1040,150,true)==0,"music search");
    }
    private static void geometry(){
        check(!AdSkipRules.cornerBounds(0,0,1080,2400,1080,2400),"page ancestor");
        check(!AdSkipRules.cornerBounds(-1,60,1040,150,1080,2400),"offscreen");
        check(!AdSkipRules.cornerBounds(870,1000,1040,1090,1080,2400),"middle strip");
        check(AdSkipRules.cornerBounds(900,2150,1060,2270,1080,2400),"bottom-right");
        check(!AdSkipRules.cornerBounds(100,2150,400,2270,1080,2400),"bottom-left is not a skip corner");
    }
    private static void session(){
        AdSkipSession t=new AdSkipSession();t.observe("com.example",1000);check(t.canInspect("com.example",1000),"opens");
        check(!t.observe("com.android.systemui",1100),"systemui overlay does not reset session");
        check("com.example".equals(t.packageName()),"package preserved across systemui");
        t.attempted("com.example/skip@3:0",1000);check(!t.canAttempt(1200),"cooldown");check(t.canAttempt(1450),"bounded retry");
        check(t.canAttemptKey("com.example/skip@3:0",1450),"second try on same key allowed for gesture fallback");
        t.attempted("com.example/skip@3:0",1450);
        check(!t.canAttemptKey("com.example/skip@3:0",2000),"third try on same key blocked");
        check(t.canInspect("com.example",1650),"accepted click is not success");t.confirmed();check(!t.canInspect("com.example",1800),"one confirmed");
        t.observe(AdSkipRules.BILI,2000);t.attempted(2000);t.confirmed();check(t.canInspect(AdSkipRules.BILI,3000),"bili double splash");t.confirmed();check(!t.canInspect(AdSkipRules.BILI,3000,true),"cap");
        t.observe("com.x",4000);for(int i=0;i<3;i++)t.attempted(4000+i*500);check(!t.canInspect("com.x",6000),"attempt cap");
        t.observe("com.y",9000);check(!t.canInspect("com.y",16000),"window expiry");check(t.canInspect("com.y",18000,true),"trusted window within 12s");check(!t.canInspect("com.y",25001,true),"trusted window expiry");
        t.suspend();check(!t.canInspect("com.y",9001),"suspend final");check(!t.observe("com.y",9002),"same package no reset");
        check(t.observe("com.miui.home",26000),"leave to launcher");t.observe("com.y",27000);check(t.canInspect("com.y",27000),"new visit resets");
    }
}
