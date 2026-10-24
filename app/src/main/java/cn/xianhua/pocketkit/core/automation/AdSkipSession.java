package cn.xianhua.pocketkit.core.automation;

/** Bounded retry budget per foreground visit; accepted clicks are not successes until verified. */
public final class AdSkipSession {
    private String pkg="";
    private long started=-1,nextAttempt;
    private int attempts,confirmed;
    private boolean suspended;
    public boolean observe(String next,long now){
        if(next==null)next="";if(next.equals(pkg))return false;
        pkg=next;started=now;nextAttempt=0;attempts=confirmed=0;suspended=false;return true;
    }
    public String packageName(){return pkg;}
    public boolean withinWindow(long now){return AdSkipRules.isWithinWindow(started,now);}
    public boolean canInspect(String current,long now){return canInspect(current,now,false);}
    public boolean canInspect(String current,long now,boolean trustedId){
        boolean trust=trustedId&&AdSkipRules.hasKnownIds(pkg);
        return current!=null&&!current.isEmpty()&&current.equals(pkg)&&!suspended&&attempts<4
                &&confirmed<(AdSkipRules.BILI.equals(pkg)?2:1)&&(trust||withinWindow(now));
    }
    public boolean canAttempt(long now){return !suspended&&attempts<4&&now>=nextAttempt;}
    public void attempted(long now){attempts++;nextAttempt=now+650;}
    public void confirmed(){confirmed++;}
    public int attempts(){return attempts;}
    public void markAttempted(){suspend();}
    public void suspend(){suspended=true;}
}
