package cn.xianhua.pocketkit.core.automation;

import java.util.Locale;
import java.util.regex.Pattern;

/** Generic, bounded splash-skip matching. Package IDs are optional hints, never requirements. */
public final class AdSkipRules {
    public static final long WINDOW_MS=6500L;
    public static final long TRUSTED_WINDOW_MS=12000L;
    public static final String BILI="tv.danmaku.bili", MUSIC="com.netease.cloudmusic";
    private static final String NUM="[0-9]{1,2}";
    private static final String UNIT="(?:s|sec|秒|″)?";
    private static final String GAP="[\\s|丨/\\\\:：·•,，()（）\\[\\]【】—\\-+>》›»×✕✖x]*";
    private static final Pattern EXPLICIT=Pattern.compile(
            "^"+GAP+"(?:"+NUM+GAP+UNIT+GAP+")?(?:(?:点击|点此|立即)?跳过(?:广告|此广告|开屏广告|开屏)?|关闭广告|skip(?:\\s*ad(?:vert|s)?)?)"+GAP+"(?:"+NUM+GAP+UNIT+")?"+GAP+"$");
    private static final Pattern COUNTDOWN_ONLY=Pattern.compile("^"+GAP+NUM+GAP+UNIT+GAP+"$");
    private static final Pattern FALSE_SKIP=Pattern.compile(
            "(?:片头|片尾|空白|静音|已读|本集|此集|下集|教程|引导|新手|登录|注册|绑定|设置|验证|更新|升级|测评|问卷|此题|本题|章节|段落|前奏|间奏|尾奏|不跳过|已跳过|自动跳过)");
    private static final Pattern AD_MARK=Pattern.compile("(?:广告|广告位|开屏|跳过广告|关闭广告|skipad)");
    private static final Pattern HAS_DIGIT=Pattern.compile("[0-9]");
    private AdSkipRules(){}

    public static String normalize(CharSequence value){
        if(value==null)return "";
        String raw=value.toString();
        if(raw.length()>60)return "";
        return raw.replaceAll("[\\s\\p{Z}\\p{Cf}]","").toLowerCase(Locale.ROOT);
    }
    public static boolean isStrictSkipLabel(CharSequence value){
        String s=normalize(value);
        return !s.isEmpty()&&s.length()<=16&&!FALSE_SKIP.matcher(s).find()&&EXPLICIT.matcher(s).matches();
    }
    /** Explicit ad/splash wording or skip+countdown ("跳过 5s", "跳过广告"); safe across warm resumes. */
    public static boolean isExplicitAdSkipLabel(CharSequence value){
        if(!isStrictSkipLabel(value))return false;
        String s=normalize(value);
        return AD_MARK.matcher(s).find()||HAS_DIGIT.matcher(s).find();
    }
    /** Used only to combine adjacent child labels inside a small skip container, never clicked alone. */
    public static boolean isCountdownLabel(CharSequence value){
        String s=normalize(value);
        return !s.isEmpty()&&s.length()<=6&&HAS_DIGIT.matcher(s).find()&&COUNTDOWN_ONLY.matcher(s).matches();
    }
    public static boolean isAdMarked(CharSequence value){
        return value!=null&&AD_MARK.matcher(normalize(value)).find();
    }
    public static boolean knownId(String pkg,String id){
        if(pkg==null||id==null||!id.startsWith(pkg+":id/"))return false;
        String name=id.substring(pkg.length()+4).toLowerCase(Locale.ROOT);
        if(name.isEmpty()||isExcludedId(name))return false;
        if(BILI.equals(pkg)&&("count_down".equals(name)||"skip".equals(name)||name.contains("splash_skip")))return true;
        boolean adOrSplash=name.contains("splash")||name.contains("open_ad")||name.contains("launch_ad")
                ||name.contains("welcome_ad")||name.contains("start_ad")||name.startsWith("tt_")
                ||name.startsWith("ksad_")||name.startsWith("gdt_")||name.startsWith("csj_")
                ||name.startsWith("ad_")||name.contains("_ad_");
        boolean skipOrTimer=name.contains("skip")||name.contains("count_down")||name.contains("countdown")||name.contains("jump");
        return adOrSplash&&skipOrTimer;
    }
    public static boolean isGenericSkipId(String pkg,String id){
        if(pkg==null||id==null||!id.startsWith(pkg+":id/"))return false;
        String name=id.substring(pkg.length()+4).toLowerCase(Locale.ROOT);
        if(name.isEmpty()||isExcludedId(name))return false;
        return name.equals("skip")||name.equals("skip_view")||name.equals("skip_btn")||name.equals("skip_button")
                ||name.equals("tv_skip")||name.equals("btn_skip")||name.equals("ll_skip")||name.equals("fl_skip")
                ||name.equals("rl_skip")||name.equals("iv_skip");
    }
    private static boolean isExcludedId(String name){
        return name.contains("intro")||name.contains("outro")||name.contains("head")||name.contains("tail")
                ||name.contains("login")||name.contains("guide")||name.contains("onboard")||name.contains("tutorial")
                ||name.contains("player")||name.contains("video")||name.contains("music_skip")||name.contains("next")
                ||name.contains("prev")||name.contains("search")||name.contains("danmaku")||name.contains("setting");
    }
    public static boolean hasKnownIds(String pkg){return pkg!=null&&!pkg.isEmpty();}
    public static boolean isSplashActivity(String activity){
        String s=activity==null?"":activity.toLowerCase(Locale.ROOT);
        return s.contains("splash")||s.contains("welcome")||s.contains("launch")||s.contains("startactivity")||s.contains("adactivity");
    }
    public static boolean excludedActivity(String pkg,String activity){
        String s=activity==null?"":activity.toLowerCase(Locale.ROOT);
        if(s.isEmpty()||isSplashActivity(s))return false;
        if(MUSIC.equals(pkg)&&(s.endsWith("settingactivity")||s.endsWith("searchactivity")))return true;
        return s.contains("permission")||s.contains("cashier")||s.contains("payment")||s.contains("payactivity")
                ||s.contains("search")||s.contains("login")||s.contains("register")||s.contains("setting")
                ||s.contains(" preference")||s.contains("player")||s.contains("videoactivity")
                ||s.contains("webview")&&s.contains("pay");
    }
    public static boolean isTransientPackage(String packageName){
        if(packageName==null)return true;
        String value=packageName.toLowerCase(Locale.ROOT);
        return value.isEmpty()||value.equals("android")||value.equals("com.android.systemui")
                ||value.contains("permissioncontroller")||value.contains("packageinstaller")
                ||value.contains("inputmethod")||value.contains(".ime")||value.contains("keyboard")
                ||value.contains("sogou.input")||value.contains("baidu.input")||value.contains("iflytek.input");
    }
    public static boolean isLauncherPackage(String packageName){
        if(packageName==null)return false;
        String value=packageName.toLowerCase(Locale.ROOT);
        return value.endsWith(".launcher")||value.contains(".launcher.")||value.equals("com.miui.home")
                ||value.equals("com.huawei.android.launcher")||value.equals("com.oppo.launcher")
                ||value.equals("com.bbk.launcher2")||value.equals("com.vivo.launcher")
                ||value.equals("com.sec.android.app.launcher")||value.equals("com.google.android.apps.nexuslauncher");
    }
    public static boolean isAllowedPackage(String packageName,String ownPackage){
        if(packageName==null)return false;
        String value=packageName.toLowerCase(Locale.ROOT);
        if(value.isEmpty()||value.equals(ownPackage)||isTransientPackage(value)||isLauncherPackage(value))return false;
        if(value.equals("com.android.settings")||value.equals("com.android.mms")||value.equals("com.android.contacts")
                ||value.equals("com.android.dialer")||value.equals("com.android.incallui"))return false;
        return !(value.equals("com.eg.android.alipaygphone")||value.equals("com.tencent.mm")
                ||value.contains("unionpay")||value.contains("wallet")||value.contains("payment")
                ||value.contains("banking")||value.startsWith("com.bank.")||value.contains(".bank."));
    }
    public static boolean smallBounds(int left,int top,int right,int bottom,int width,int height){
        if(width<=0||height<=0||left<0||top<0||right<=left||bottom<=top||right>width||bottom>height)return false;
        long w=right-left,h=bottom-top;
        long minW=Math.max(16,width/36),minH=Math.max(12,height/90);
        return w>=minW&&h>=minH&&w<=width*38L/100&&h<=height*11L/100&&w*h<=(long)width*height/24;
    }
    /** Right-hand top/bottom corners where standard splash-skip pills sit. */
    public static boolean rightCornerBounds(int left,int top,int right,int bottom,int width,int height){
        if(!smallBounds(left,top,right,bottom,width,height))return false;
        int cy=(top+bottom)/2,cx=(left+right)/2;
        return cx>=width*56L/100&&(cy<=height*26L/100||cy>=height*74L/100);
    }
    /** Corner strips where splash-skip controls live; the middle of a page is never one. */
    public static boolean cornerBounds(int left,int top,int right,int bottom,int width,int height){
        if(rightCornerBounds(left,top,right,bottom,width,height))return true;
        if(!smallBounds(left,top,right,bottom,width,height))return false;
        int cy=(top+bottom)/2,cx=(left+right)/2;
        return cx<=width*38L/100&&cy<=height*22L/100;
    }
    public static boolean isCandidate(CharSequence label,int left,int top,int right,int bottom,int width,int height){
        if(!isStrictSkipLabel(label))return false;
        return isExplicitAdSkipLabel(label)?cornerBounds(left,top,right,bottom,width,height):rightCornerBounds(left,top,right,bottom,width,height);
    }
    /**
     * 100 explicit splash-ad ID, 90 explicit ad/countdown skip label, 75 generic skip ID in right corner,
     * 70 bare "跳过" in right corner inside fresh launch window. Bare numbers never match alone.
     */
    public static int score(String pkg,String activity,String id,CharSequence text,CharSequence desc,
                            int l,int t,int r,int b,int w,int h,boolean withinWindow){
        if(excludedActivity(pkg,activity)||!cornerBounds(l,t,r,b,w,h))return 0;
        if(!isAllowedPackage(pkg,""))return 0;
        if(knownId(pkg,id))return 100;
        if(isExplicitAdSkipLabel(text)||isExplicitAdSkipLabel(desc))return 90;
        if(!withinWindow&&!isSplashActivity(activity))return 0;
        if(!rightCornerBounds(l,t,r,b,w,h))return 0;
        if(isGenericSkipId(pkg,id)&&(text==null||text.length()==0||isStrictSkipLabel(text)||isCountdownLabel(text)))return 75;
        if(isStrictSkipLabel(text)||isStrictSkipLabel(desc))return 70;
        return 0;
    }
    public static boolean isWithinWindow(long started,long now){return started>=0&&now>=started&&now-started<=WINDOW_MS;}
    public static boolean isWithinTrustedWindow(long started,long now){return started>=0&&now>=started&&now-started<=TRUSTED_WINDOW_MS;}
}
