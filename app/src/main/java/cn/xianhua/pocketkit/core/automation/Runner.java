package cn.xianhua.pocketkit.core.automation;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Single-worker, cancellable script executor. Backend calls may wait, never on the UI thread. */
public final class Runner {
    public interface Backend {
        boolean isTargetAvailable(String targetPackage);
        void tap(int x, int y, long timeoutMs, Control control) throws Exception;
        void longPress(int x, int y, long durationMs, long timeoutMs, Control control) throws Exception;
        void swipe(int x, int y, int x2, int y2, long durationMs, long timeoutMs, Control control) throws Exception;
        default void swipePath(int[] points, long durationMs, long timeoutMs, Control control) throws Exception {
            swipe(points[0], points[1], points[points.length - 2], points[points.length - 1], durationMs, timeoutMs, control);
        }
        void inputText(String text, long timeoutMs, Control control) throws Exception;
        void globalAction(Step.Type type, long timeoutMs, Control control) throws Exception;
        Match find(Step step, Map<String, String> variables, long timeoutMs, Control control) throws Exception;
        String readText(Step step, Map<String, String> variables, long timeoutMs, Control control) throws Exception;
        default void beforeAction(Script script, Control control) throws Exception { }
        default int[] mapPoint(Script script, int x, int y, Control control) throws Exception { return new int[]{x, y}; }
        default int[] mapPath(Script script, int[] points, Control control) throws Exception {
            int[] mapped = new int[points.length];
            for (int i = 0; i < points.length; i += 2) {
                int[] point = mapPoint(script, points[i], points[i + 1], control);
                mapped[i] = point[0]; mapped[i + 1] = point[1];
            }
            return mapped;
        }
        default void cancelPending() { }
    }
    public interface Listener { void onStatus(String status); void onFinished(String status); }
    public interface Control { boolean isCancelled(); boolean isPaused(); void awaitIfPaused() throws InterruptedException; }
    private final Backend backend; private final Listener listener; private final Object pauseLock = new Object();
    private volatile boolean cancelled; private volatile boolean paused; private volatile String status = "idle"; private volatile Thread worker;
    private volatile long deadlineNanos = Long.MAX_VALUE; private volatile String targetPackage = ""; private volatile Script script;
    private final Map<String, String> variables = new HashMap<>(); private Match lastMatch = Match.notFound();

    public Runner(Backend backend, Listener listener) { if (backend == null) throw new NullPointerException("backend"); this.backend=backend; this.listener=listener; }
    public synchronized void start(Script source) {
        if (worker != null) throw new IllegalStateException("runner already active"); source.validate(); Script copy=source.copy(); script=copy; cancelled=false; paused=false; variables.clear(); lastMatch=Match.notFound(); targetPackage=copy.targetPackage == null ? "" : copy.targetPackage; deadlineNanos=Long.MAX_VALUE;
        worker=new Thread(() -> run(copy), "PocketKit-Automation"); worker.start();
    }
    public void pause() { if (worker != null) { paused=true; setStatus("paused"); } }
    public void resume() { synchronized(pauseLock){paused=false;pauseLock.notifyAll();} if(worker!=null)setStatus("running"); }
    public void cancel(String reason) { cancelled=true; backend.cancelPending(); synchronized(pauseLock){paused=false;pauseLock.notifyAll();} Thread t=worker; if(t!=null&&t!=Thread.currentThread())t.interrupt(); if(t!=null)setStatus(reason==null?"cancelled":reason); }
    public boolean isActive(){return worker!=null&&worker.isAlive();} public boolean isPaused(){return paused;} public String status(){return status;}
    public Map<String,String> variablesSnapshot(){synchronized(variables){return Collections.unmodifiableMap(new HashMap<>(variables));}} public Match lastMatch(){return lastMatch;}

    private void run(Script value) {
        String outcome="completed";
        try {
            setStatus("scheduled"); if(value.startDelayMs>0)waitMillis(value.startDelayMs); checkBasic();
            deadlineNanos=value.timeoutMs==0?Long.MAX_VALUE:safeAdd(System.nanoTime(),value.timeoutMs*1_000_000L); setStatus("running"); executeRounds(value); if(cancelled)outcome=status;
        } catch(InterruptedException ex){Thread.currentThread().interrupt();outcome=cancelled?status:"interrupted";} catch(Exception ex){outcome="failed: "+safeMessage(ex);} finally {backend.cancelPending();setStatus(outcome);worker=null;script=null;if(listener!=null)listener.onFinished(outcome);}
    }
    /** Variables survive rounds, matching LOOP semantics; only the recognition result is per-round. */
    private void executeRounds(Script value)throws Exception{for(int round=1;value.repeatCount==0||round<=value.repeatCount;round++){check();lastMatch=Match.notFound();executeList(value.steps);if(value.repeatCount!=0&&round>=value.repeatCount)break;waitMillis(Math.max(1,value.repeatIntervalMs));}}
    private void executeList(List<Step> list)throws Exception{for(Step step:list){check();setStatus("step:"+step.type.name());execute(step);}}
    private void execute(Step s)throws Exception{check();switch(s.type){
        case TAP: actionTap(s);break; case LONG_PRESS:actionLongPress(s);break; case SWIPE:actionSwipe(s);break; case WAIT:waitMillis(s.durationMs);break; case WAIT_UNTIL:waitUntil(s.atEpochMs);break;
        case INPUT_TEXT:prepare();backend.inputText(expand(s.text),timeout(s),control);break; case BACK:case HOME:case RECENTS:prepare();backend.globalAction(s.type,timeout(s),control);break;
        case WAIT_TEXT:case WAIT_IMAGE:waitForMatch(s);if(s.clickMatch)clickLastMatch(s);break; case OCR:prepare();synchronized(variables){variables.put(s.variable,backend.readText(s,variables,timeout(s),control));}break;
        case IF_TEXT:case IF_IMAGE:boolean hit=find(s);if(hit&&s.clickMatch)clickLastMatch(s);branch(s,hit);break; case LOOP:loop(s);break; case SET_VARIABLE:synchronized(variables){variables.put(s.variable,expand(s.value));}break;
        case IF_VARIABLE:branch(s,compare(variable(s.variable),expand(s.value),s.operator));break; case STOP:cancel(s.label.isEmpty()?"stopped":s.label);break; default:throw new IllegalArgumentException("unsupported step "+s.type);
    }}
    private void actionTap(Step s)throws Exception{prepare();int[]p=s.useLastMatch?coordinates(s):backend.mapPoint(script,s.x,s.y,control);backend.tap(p[0],p[1],timeout(s),control);}
    private void actionLongPress(Step s)throws Exception{prepare();int[]p=s.useLastMatch?coordinates(s):backend.mapPoint(script,s.x,s.y,control);backend.longPress(p[0],p[1],s.durationMs==0?600:s.durationMs,timeout(s),control);}
    private void actionSwipe(Step s)throws Exception {
        prepare();
        if (s.gesturePath.length > 0 && !s.useLastMatch) {
            int[] path = backend.mapPath(script, s.gesturePath, control);
            backend.swipePath(path, s.durationMs == 0 ? 300 : s.durationMs, timeout(s), control);
        } else {
            int[] a = s.useLastMatch ? coordinates(s) : backend.mapPoint(script, s.x, s.y, control);
            int[] b = backend.mapPoint(script, s.x2, s.y2, control);
            backend.swipe(a[0], a[1], b[0], b[1], s.durationMs == 0 ? 300 : s.durationMs, timeout(s), control);
        }
    }
    private int[] coordinates(Step s)throws Exception{if(s.useLastMatch){if(!lastMatch.found)throw new IllegalStateException("识别失败，未执行坐标动作");return new int[]{lastMatch.centerX(),lastMatch.centerY()};}return new int[]{s.x,s.y};}
    /** A Match is already current-screen physical pixels, so it is tapped as-is, never re-mapped from recorded coordinates. */
    private void clickLastMatch(Step s)throws Exception{if(!lastMatch.found)throw new IllegalStateException("识别失败，未点击匹配位置");prepare();backend.tap(lastMatch.centerX(),lastMatch.centerY(),timeout(s),control);}
    private boolean find(Step s)throws Exception{lastMatch=Match.notFound();prepare();Match m=backend.find(s,variables,timeout(s),control);if(m!=null&&m.found){lastMatch=m;return true;}lastMatch=Match.notFound();return false;}
    private void waitForMatch(Step s)throws Exception{long end=timeoutDeadline(s);while(System.nanoTime()<end){check();if(find(s))return;long left=remainingMs(end);if(left<=0)break;waitMillis(Math.min(left,s.intervalMs==0?100:s.intervalMs));}lastMatch=Match.notFound();throw new IllegalStateException("等待识别超时");}
    private void branch(Step s,boolean yes)throws Exception{executeList(yes?s.children:s.otherwise);}
    private void loop(Step s)throws Exception{int n=0;while(s.count==0||n++<s.count){check();executeList(s.children);if(s.count==0||n<s.count)waitMillis(Math.max(1,s.intervalMs));}}
    private boolean compare(String actual,String expected,String operator){String op=operator==null||operator.isEmpty()?"equals":operator;if("equals".equals(op)||"==".equals(op))return actual.equals(expected);if("notEquals".equals(op)||"!=".equals(op))return !actual.equals(expected);if("contains".equals(op))return actual.contains(expected);if("regex".equals(op)){if(actual.length()>4096||expected.length()>256)throw new IllegalArgumentException("正则输入过长");try{return java.util.regex.Pattern.compile(expected).matcher(new DeadlineText(actual)).matches();}catch(RegexAbort ex){throw new IllegalStateException(ex.getMessage());}}double a,b;try{a=Double.parseDouble(actual);b=Double.parseDouble(expected);}catch(NumberFormatException ex){throw new IllegalArgumentException("数值比较需要数字",ex);}return ("greater".equals(op)||">".equals(op))?a>b:("greaterOrEqual".equals(op)||">=".equals(op))?a>=b:("less".equals(op)||"<".equals(op))?a<b:a<=b;}
    private String variable(String name){synchronized(variables){return variables.getOrDefault(name==null?"":name,"");}}
    private String expand(String value){if(value==null||value.isEmpty())return value==null?"":value;String marker="$"+"{";StringBuilder out=new StringBuilder();int i=0;while(i<value.length()){int start=value.indexOf(marker,i);if(start<0){out.append(value,i,value.length());break;}out.append(value,i,start);int end=value.indexOf('}',start+2);if(end<0){out.append(value,start,value.length());break;}out.append(variable(value.substring(start+2,end)));i=end+1;}return out.toString();}
    private final Control control=new Control(){public boolean isCancelled(){return cancelled||timedOut();}public boolean isPaused(){return paused;}public void awaitIfPaused()throws InterruptedException{synchronized(pauseLock){while(paused&&!cancelled&&!timedOut())pauseLock.wait(100);}checkBasic();}};
    private void prepare()throws Exception{check();backend.beforeAction(script,control);check();}
    private void check()throws InterruptedException{checkBasic();control.awaitIfPaused();}
    private void checkBasic()throws InterruptedException{if(cancelled)throw new InterruptedException("cancelled");if(timedOut())throw new IllegalStateException("脚本执行超时");}
    private void waitMillis(long ms)throws InterruptedException{long end=safeAdd(System.nanoTime(),Math.max(0,ms)*1_000_000L);while(System.nanoTime()<end){check();long left=end-System.nanoTime();Thread.sleep(Math.min(100,Math.max(1,left/1_000_000L)));}}
    private void waitUntil(long epoch)throws InterruptedException{long delta=epoch-System.currentTimeMillis();if(delta>0)waitMillis(delta);}
    private long timeout(Step s){long fallback=(s.type==Step.Type.SWIPE||s.type==Step.Type.LONG_PRESS)?Math.max(30_000,s.durationMs+2000):30_000;long requested=s.timeoutMs==0?fallback:s.timeoutMs;long budget=remainingBudgetMs();return Math.min(requested,budget);} private long timeoutDeadline(Step s){long local=s.timeoutMs==0?30_000:s.timeoutMs;long global=deadlineNanos==Long.MAX_VALUE?Long.MAX_VALUE:Math.max(1,(deadlineNanos-System.nanoTime())/1_000_000L);return safeAdd(System.nanoTime(),Math.min(local,global)*1_000_000L);}
    private long remainingMs(long end){return Math.max(1,(end-System.nanoTime())/1_000_000L);} private long remainingBudgetMs(){if(deadlineNanos==Long.MAX_VALUE)return Long.MAX_VALUE;long left=(deadlineNanos-System.nanoTime())/1_000_000L;if(left<=0)throw new IllegalStateException("脚本执行超时");return left;} private boolean timedOut(){return System.nanoTime()>=deadlineNanos;}
    private final class DeadlineText implements CharSequence{private final String value;DeadlineText(String v){value=v;}public int length(){return value.length();}public char charAt(int i){if(cancelled||timedOut())throw new RegexAbort("正则匹配超时或取消");return value.charAt(i);}public CharSequence subSequence(int a,int b){return value.substring(a,b);}}
    private static final class RegexAbort extends RuntimeException{RegexAbort(String message){super(message);}}
    private static long safeAdd(long a,long b){long r=a+b;return r<a?Long.MAX_VALUE:r;} private static String safeMessage(Exception ex){return ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();}
    private void setStatus(String value){status=value;if(listener!=null)listener.onStatus(value);}
}
