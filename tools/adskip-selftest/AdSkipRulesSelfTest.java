import cn.xianhua.pocketkit.core.automation.AdSkipRules;
import cn.xianhua.pocketkit.core.automation.AdSkipSession;

public final class AdSkipRulesSelfTest {
    private static int checks;
    public static void main(String[] args){labels();generic();known();geometry();session();System.out.println("AdSkipRulesSelfTest PASS: "+checks+" checks");}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void labels(){
        for(String s:new String[]{"跳过"," 跳过 ","跳过广告","跳过 5s","5秒 | 跳过","跳过（3秒）","Skip","SKIP 5s","Skip Ad","5s Skip","跳\u200b过","跳过此广告","跳过开屏广告","3s|跳过"})check(AdSkipRules.isStrictSkipLabel(s),"accept "+s);
        for(String s:new String[]{null,"","关闭","×","3s","不跳过","跳过这首歌","跳过验证","点击跳过登录","搜索跳过广告","Skip song","skip payment","跳过登录","跳过设置"})check(!AdSkipRules.isStrictSkipLabel(s),"reject "+s);
        for(String s:new String[]{"5","5s","12秒","3"})check(AdSkipRules.isCountdownLabel(s),"countdown "+s);
        for(String s:new String[]{"5.5","五分钟","一","1000",""})check(!AdSkipRules.isCountdownLabel(s),"no countdown "+s);
    }
    private static int s(String pkg,String act,String id,String text,int l,int t,int r,int b,boolean w){return AdSkipRules.score(pkg,act,id,text,null,l,t,r,b,1080,2400,w);}
    private static void generic(){
        // Any application: same corner skip control, no per-app rule needed.
        check(s("com.example.video","com.example.video.SplashActivity",null,"跳过 3",870,60,1040,150,true)==70,"generic top-right");
        check(s("com.any.reader","ui.Launch",null,"Skip Ad",900,2150,1050,2260,true)==70,"generic bottom-right english");
        check(s("com.any.app","Main","com.any:id/whatever","5",870,70,1030,140,true)==45,"generic top countdown");
        check(s("com.any.app","Main",null,"跳过",540,1100,600,1180,true)==0,"never the middle of a page");
        check(s("com.any.app","Main",null,"跳过",120,60,300,140,true)==70,"top-left also allowed");
        check(s("com.any.app","Main",null,"跳过",600,1200,1000,1320,true)==0,"central dialog rejected");
        check(s("com.any.app","Main",null,"跳过",870,60,1040,150,false)==0,"stale text outside window");
        check(s("com.any.app","com.any.SearchActivity",null,"跳过",870,60,1040,150,true)==0,"search page excluded");
        check(s("com.any.app","com.any.LoginActivity",null,"跳过",870,60,1040,150,true)==0,"login page excluded");
        check(s("com.any.app","com.any.PaymentActivity",null,"跳过",870,60,1040,150,true)==0,"payment page excluded");
        check(s("com.example.wallet","Main",null,"跳过",870,60,1040,150,true)==0,"wallet package excluded");
        check(!AdSkipRules.isAllowedPackage("cn.xianhua.pocketkit","cn.xianhua.pocketkit"),"own package rejected by package gate");
    }
    private static void known(){
        check(s(AdSkipRules.BILI,"tv.danmaku.bili.MainActivityV2","tv.danmaku.bili:id/count_down","5",870,60,1040,150,true)==100,"observed bili ID");
        check(s(AdSkipRules.BILI,"tv.danmaku.bili.MainActivityV2","tv.danmaku.bili:id/skip","",870,60,1040,150,false)==100,"known ID survives hot splash");
        int foreign=s("com.other.app","Main","tv.danmaku.bili:id/count_down","5",870,60,1040,150,true);
        check(foreign<100,"a foreign package ID never scores as a known rule: "+foreign);
        check(AdSkipRules.knownId("com.other.app","tv.danmaku.bili:id/count_down")==false,"known ID is package bound");
        check(s(AdSkipRules.MUSIC,"com.netease.cloudmusic.activity.LoadingActivity",null,"跳过 5",870,60,1040,150,true)==70,"music countdown");
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
        t.attempted(1000);check(!t.canAttempt(1200),"cooldown");check(t.canAttempt(1650),"bounded retry");
        check(t.canInspect("com.example",1650),"accepted click is not success");t.confirmed();check(!t.canInspect("com.example",1800),"one confirmed");
        t.observe(AdSkipRules.BILI,2000);t.attempted(2000);t.confirmed();check(t.canInspect(AdSkipRules.BILI,3000),"bili double splash");t.confirmed();check(!t.canInspect(AdSkipRules.BILI,3000,true),"cap");
        t.observe("com.x",4000);for(int i=0;i<4;i++)t.attempted(4000+i*700);check(!t.canInspect("com.x",8000),"attempt cap");
        t.observe("com.y",9000);check(!t.canInspect("com.y",25001),"window expiry");check(!t.canInspect("com.y",25001,true),"unknown package has no window exemption");
        t.suspend();check(!t.canInspect("com.y",9001),"suspend final");check(!t.observe("com.y",9002),"same package no reset");
        check(t.observe("com.launcher",26000),"leave");t.observe("com.y",27000);check(t.canInspect("com.y",27000),"new visit resets");
    }
}
