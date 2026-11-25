package cn.xianhua.pocketkit.core.automation;

/** Bounded retry budget per foreground visit; accepted clicks are not successes until verified. */
public final class AdSkipSession {
    private String pkg="",lastKey="";
    private long started=-1,nextAttempt;
    private int attempts,confirmed,keyAttempts;
    private boolean suspended;
    public boolean observe(String next,long now){
        if(next==null||AdSkipRules.isTransientPackage(next))return false;
        if(next.equals(pkg))return false;
        pkg=next;started=now;nextAttempt=0;attempts=confirmed=keyAttempts=0;lastKey="";suspended=false;return true;
    }
    public void onSplashActivity(long now){
        if(pkg.isEmpty()||now-started<1500)return;
        started=now;nextAttempt=0;attempts=confirmed=keyAttempts=0;lastKey="";suspended=false;
    }
    public String packageName(){return pkg;}
    public boolean withinWindow(long now){return AdSkipRules.isWithinWindow(started,now);}
    public boolean withinTrustedWindow(long now){return AdSkipRules.isWithinTrustedWindow(started,now);}
    public boolean canInspect(String current,long now){return canInspect(current,now,false);}
    public boolean canInspect(String current,long now,boolean trustedSignal){
        boolean trust=trustedSignal&&withinTrustedWindow(now);
        return current!=null&&!current.isEmpty()&&current.equals(pkg)&&!suspended&&attempts<3
                &&confirmed<(AdSkipRules.BILI.equals(pkg)?2:1)&&(trust||withinWindow(now));
    }
    public boolean canAttempt(long now){return !suspended&&attempts<3&&now>=nextAttempt;}
    public boolean canAttemptKey(String key,long now){
        if(!canAttempt(now))return false;
        return key==null||!key.equals(lastKey)||keyAttempts<2;
    }
    public int keyAttempts(String key){return key!=null&&key.equals(lastKey)?keyAttempts:0;}
    public void attempted(String key,long now){
        attempts++;
        if(key!=null&&key.equals(lastKey))keyAttempts++;
        else{lastKey=key==null?"":key;keyAttempts=1;}
        nextAttempt=now+420;
    }
    public void attempted(long now){attempted("",now);}
    public void confirmed(){confirmed++;lastKey="";keyAttempts=0;}
    public int attempts(){return attempts;}
    public void markAttempted(){suspend();}
    public void suspend(){suspended=true;}
}
