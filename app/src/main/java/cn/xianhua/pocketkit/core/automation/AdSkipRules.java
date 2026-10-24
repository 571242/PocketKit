package cn.xianhua.pocketkit.core.automation;

import java.util.Locale;
import java.util.regex.Pattern;

/** Generic, bounded splash-skip matching. Package IDs are optional hints, never requirements. */
public final class AdSkipRules {
    public static final long WINDOW_MS=15000L;
    public static final String BILI="tv.danmaku.bili", MUSIC="com.netease.cloudmusic";
    private static final String NUM="[0-9]{1,3}";
    private static final String UNIT="(?:s|S|秒|″)?";
    private static final String GAP="[\\s|丨:：·•()（）\\[\\]【】—\\-+]*";
    private static final Pattern EXPLICIT=Pattern.compile(
            "^(?:"+NUM+GAP+UNIT+GAP+")?(?:跳过(?:广告|此广告|开屏广告)?|skip(?:\\s*ad(?:vert)?)?)"+GAP+"(?:"+NUM+GAP+UNIT+")?"+GAP+"$");
    private static final Pattern COUNTDOWN_ONLY=Pattern.compile("^(?:"+NUM+GAP+UNIT+")$");
    private static final Pattern AD_MARK=Pattern.compile("(?:广告|广告位|开屏)");
    private AdSkipRules(){}

    public static String normalize(CharSequence value){
        if(value==null)return "";
        String raw=value.toString();
        if(raw.length()>80)return "";
        return raw.replaceAll("[\\s\\p{Z}\\p{Cf}]","").toLowerCase(Locale.ROOT);
    }
    public static boolean isStrictSkipLabel(CharSequence value){
        String s=normalize(value);
        return !s.isEmpty()&&s.length()<=20&&EXPLICIT.matcher(s).matches();
    }
    /** A bare number/seconds label is only accepted in the two corner strips that ad SDKs use. */
    public static boolean isCountdownLabel(CharSequence value){
        String s=normalize(value);
        return !s.isEmpty()&&s.length()<=5&&COUNTDOWN_ONLY.matcher(s).matches();
    }
    public static boolean isAdMarked(CharSequence value){
        return value!=null&&AD_MARK.matcher(value.toString()).find();
    }
    public static boolean knownId(String pkg,String id){
        if(pkg==null||id==null)return false;
        if(!id.startsWith(pkg+":id/"))return false;
        String name=id.substring(pkg.length()+4);
        return BILI.equals(pkg)&&("count_down".equals(name)||"skip".equals(name));
    }
    public static boolean hasKnownIds(String pkg){return BILI.equals(pkg);}
    public static boolean excludedActivity(String pkg,String activity){
        String s=activity==null?"":activity.toLowerCase(Locale.ROOT);
        if(s.isEmpty())return false;
        if(MUSIC.equals(pkg)&&(s.endsWith("settingactivity")||s.endsWith("searchactivity")))return true;
        return s.contains("permission")||s.contains("cashier")||s.contains("payment")||s.contains("payactivity")
                ||s.contains("search")||s.contains("login")||s.contains("setting")||s.contains("webview")&&s.contains("pay");
    }
    public static boolean isAllowedPackage(String packageName,String ownPackage){
        if(packageName==null)return false;
        String value=packageName.toLowerCase(Locale.ROOT);
        if(value.isEmpty()||value.equals(ownPackage)||value.equals("android"))return false;
        if(value.equals("com.android.settings")||value.equals("com.android.systemui")
                ||value.contains("permissioncontroller")||value.contains("packageinstaller")
                ||value.endsWith(".launcher")||value.contains(".launcher.")||value.equals("com.miui.home"))return false;
        return !(value.equals("com.eg.android.alipaygphone")||value.equals("com.tencent.mm")
                ||value.contains("unionpay")||value.contains("wallet")||value.contains("payment")
                ||value.contains("banking")||value.startsWith("com.bank.")||value.contains(".bank."));
    }
    public static boolean smallBounds(int left,int top,int right,int bottom,int width,int height){
        if(width<=0||height<=0||left<0||top<0||right<=left||bottom<=top||right>width||bottom>height)return false;
        long w=right-left,h=bottom-top;
        return w<=width*70L/100&&h<=height*22L/100&&w*h<=(long)width*height/8;
    }
    /** Corner strips where splash-skip controls live; the middle of a form page is never one. */
    public static boolean cornerBounds(int left,int top,int right,int bottom,int width,int height){
        if(!smallBounds(left,top,right,bottom,width,height))return false;
        int cy=(top+bottom)/2,cx=(left+right)/2;
        return cy<=height*32L/100 || (cy>=height*72L/100 && cx>=width*50L/100);
    }
    public static boolean isCandidate(CharSequence label,int left,int top,int right,int bottom,int width,int height){
        return isStrictSkipLabel(label)&&cornerBounds(left,top,right,bottom,width,height);
    }
    /**
     * 100 known app ID, 70 explicit skip text (or description), 45 corner countdown only inside the fresh window.
     * 0 for protected packages/activities, non-corner nodes, page-sized containers.
     */
    public static int score(String pkg,String activity,String id,CharSequence text,CharSequence desc,
                            int l,int t,int r,int b,int w,int h,boolean withinWindow){
        if(excludedActivity(pkg,activity)||!cornerBounds(l,t,r,b,w,h))return 0;
        if(!isAllowedPackage(pkg,""))return 0;
        if(knownId(pkg,id))return 100;
        if(!withinWindow)return 0;
        if(isCandidate(text,l,t,r,b,w,h)||isCandidate(desc,l,t,r,b,w,h))return 70;
        if(isCountdownLabel(text)&&t<=h/12)return 45;
        return 0;
    }
    public static boolean isWithinWindow(long started,long now){return started>=0&&now>=started&&now-started<=WINDOW_MS;}
}
