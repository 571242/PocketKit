package cn.xianhua.pocketkit;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import cn.xianhua.pocketkit.core.automation.Script;
import cn.xianhua.pocketkit.core.automation.Step;

/** Native step editor: the rendered tree is the persisted children/otherwise tree. */
public final class AutomationEditor extends LinearLayout {
    public interface Listener { void requestPick(Step step,String mode); void chooseTemplate(Step step); void changed(); }
    private final AutomationToolsActivity activity; private final Script script; private final Listener listener; private EditText nameField; private String invalid; private final java.util.Map<String,String> rawFields=new java.util.LinkedHashMap<>(); private final java.util.List<Runnable> fieldReaders=new java.util.ArrayList<>(); private String fieldOwner;
    private final Set<String> expanded=new HashSet<>(); private boolean advancedOpen;
    /** The one word the user has to find: a recognised target is clicked instead of being reported. */
    static final String CLICK_MATCH="识别到后直接点击目标";
    public AutomationEditor(AutomationToolsActivity a, Script s, Listener l){super(a);activity=a;script=s;listener=l;setOrientation(VERTICAL);rebuild();}
    public Listener listenerForReload(){return listener;}
    public void rebuild(){
        removeAllViews();fieldReaders.clear();fieldOwner="script:"+script.id;if(script.steps==null)script.steps=new ArrayList<>();if(script.repeatCount<0)script.repeatCount=0;if(script.repeatIntervalMs<0)script.repeatIntervalMs=0;
        nameField=addText(this,"脚本名称",script.name,v->script.name=v);
        addRepeat(this);
        Button advanced=new Button(activity);advanced.setText(advancedOpen?"收起高级设置":"高级设置（启动延迟 / 绑定应用 / 总时限）");advanced.setAllCaps(false);ToolUi.text(advanced,activity.accent,14);ToolUi.touch(advanced,activity.dp(48));advanced.setBackground(ToolUi.ripple(activity,activity.surface,activity.dp(10),Color.TRANSPARENT,0));
        advanced.setOnClickListener(v->{if(validateVisible()){advancedOpen=!advancedOpen;rebuild();}});addView(advanced,new LinearLayout.LayoutParams(-1,activity.dp(48)));
        if(advancedOpen){addText(this,"目标应用包名（可选）",script.targetPackage,v->script.targetPackage=v);addLong(this,"启动延迟（秒）",script.startDelayMs/1000,v->script.startDelayMs=v*1000);addLong(this,"总时限（秒，0=不限制）",script.timeoutMs/1000,v->script.timeoutMs=v*1000);}
        labelInto(this,"动作清单（点一行可展开编辑）");
        renderList(this,script.steps,0,"动作");
        if(script.steps.isEmpty())labelInto(this,"还没有动作");
        addBlock(this,"添加动作",script.steps,-1);
    }
    /** Script-level repetition: visible, not folded into the advanced block nobody opens. */
    private void addRepeat(LinearLayout parent){
        String[] modes={"只执行一次","指定次数","直到手动停止"};
        int mode=script.repeatCount==0?2:script.repeatCount==1?0:1;
        String countTitle="重复次数（至少 2 次）",countKey=fieldOwner+"|"+countTitle;
        LinearLayout box=new LinearLayout(activity);box.setOrientation(VERTICAL);box.setPadding(activity.dp(10),activity.dp(8),activity.dp(10),activity.dp(8));box.setBackground(ToolUi.fill(activity.surface,activity.dp(10),activity.accent,activity.dp(1)));
        labelInto(box,"脚本重复运行");
        TextView state=new TextView(activity);ToolUi.text(state,activity.accent,15);box.addView(state);
        Runnable updateSummary=()->{String value=repeatSummary();state.setText("当前："+value);state.setContentDescription(value);};updateSummary.run();
        Spinner spinner=new Spinner(activity);spinner.setAdapter(choices(modes));spinner.setMinimumHeight(activity.dp(48));
        final boolean[] first={true};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> x){}public void onItemSelected(AdapterView<?> x,View v,int pos,long id){
            if(first[0]){first[0]=false;return;}if(pos==mode)return;
            if(!validateVisible()){rebuild();return;}
            rawFields.remove(countKey);
            if(pos==0)script.repeatCount=1;else if(pos==2)script.repeatCount=0;else script.repeatCount=Math.max(2,script.repeatCount);
            rebuild();
        }});
        spinner.setSelection(mode,false);box.addView(spinner);
        if(mode==1){
            addText(box,countTitle,String.valueOf(script.repeatCount),v->{
                try{int parsed=Integer.parseInt(v.trim());if(parsed<2)throw new NumberFormatException();script.repeatCount=parsed;updateSummary.run();invalid=null;}catch(RuntimeException ex){invalid="指定次数必须是至少 2 的整数；单次或持续运行请切换上方选项";}
            }).setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        addText(box,"轮次间隔（毫秒，0=不等待）",String.valueOf(script.repeatIntervalMs),v->{
            try{long parsed=Long.parseLong(v.trim());if(parsed<0||parsed>7L*24*3600*1000)throw new NumberFormatException();script.repeatIntervalMs=parsed;updateSummary.run();invalid=null;}catch(RuntimeException ex){invalid="轮次间隔必须是 0 到 7 天之间的毫秒整数";}
        }).setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(box,new LinearLayout.LayoutParams(-1,-2));
    }
    private String repeatSummary(){
        String base=script.repeatCount==0?"直到手动停止":script.repeatCount==1?"只执行一次":script.repeatCount+" 次";
        if(script.repeatCount==1)return base;
        return base+" · 每轮间隔 "+script.repeatIntervalMs+" 毫秒";
    }
    /** A step the user just created opens straight away; everything else stays compact. */
    private void addStep(List<Step> list,Step.Type type){addStepAt(list,-1,type);}
    /** at<0 appends; otherwise the new action lands right after the row the user tapped. */
    private void addStepAt(List<Step> list,int at,Step.Type type){
        if(!validateVisible())return;
        Step step=new Step(type);if(at<0||at>list.size())list.add(step);else list.add(at,step);
        expanded.add(step.id);rebuild();
    }
    public void pull(){String firstError=null;for(Runnable reader:fieldReaders){invalid=null;try{reader.run();}catch(RuntimeException ex){invalid=ex.getMessage();}if(firstError==null&&invalid!=null)firstError=invalid;}invalid=firstError;if(invalid!=null)throw new IllegalArgumentException(invalid);if(nameField!=null)script.name=nameField.getText().toString().trim();}
    public Bundle rawSnapshot(){Bundle b=new Bundle();for(java.util.Map.Entry<String,String> field:rawFields.entrySet())b.putString(field.getKey(),field.getValue());b.putString("@expanded",String.join(",",expanded));b.putString("@advanced",String.valueOf(advancedOpen));return b;}
    public void restoreRaw(Bundle b){if(b==null)return;rawFields.clear();expanded.clear();for(String key:b.keySet())if(!key.startsWith("@"))rawFields.put(key,b.getString(key,""));for(String id:b.getString("@expanded","").split(","))if(!id.isEmpty())expanded.add(id);advancedOpen=Boolean.parseBoolean(b.getString("@advanced","false"));rebuild();}
    public void refreshStep(String id){rawFields.keySet().removeIf(key->key.startsWith(id+"|"));rebuild();}
    private boolean validateVisible(){try{pull();return true;}catch(RuntimeException error){android.widget.Toast.makeText(activity,error.getMessage(),android.widget.Toast.LENGTH_LONG).show();return false;}}
    public Step find(String id){return findIn(script.steps,id);}private Step findIn(List<Step> list,String id){if(list==null||id==null)return null;for(Step s:list){if(id.equals(s.id))return s;Step x=findIn(s.children,id);if(x!=null)return x;x=findIn(s.otherwise,id);if(x!=null)return x;}return null;}
    private void renderList(LinearLayout parent,List<Step> list,int depth,String branch){if(list==null)return;for(int i=0;i<list.size();i++)renderStep(parent,list,i,depth,branch);}
    private void renderStep(LinearLayout parent,List<Step> owner,int index,int depth,String branch){
        Step s=owner.get(index);if(s.id==null||s.id.isEmpty())s.id=UUID.randomUUID().toString();boolean open=expanded.contains(s.id);
        LinearLayout box=new LinearLayout(activity);box.setOrientation(VERTICAL);box.setPadding(activity.dp(depth>0?12:8),activity.dp(6),0,activity.dp(4));
        LinearLayout head=row();head.setMinimumHeight(activity.dp(48));head.setContentDescription(branch+" "+(index+1)+" "+summary(s));
        TextView title=new TextView(activity);title.setText((depth>0?"↳ ":"")+branch+" "+(index+1)+" · "+summary(s));ToolUi.text(title,activity.ink,15);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button more=small(open?"收起":"展开");more.setOnClickListener(v->toggle(s));head.addView(more,new LinearLayout.LayoutParams(activity.dp(64),activity.dp(48)));
        head.setFocusable(true);head.setOnClickListener(v->toggle(s));
        box.addView(head);
        if(open){
            fieldOwner=s.id;
            LinearLayout bar=row();Spinner type=typeSpinner(s);bar.addView(type,new LinearLayout.LayoutParams(0,activity.dp(52),1));
            Button menu=small("更多");menu.setContentDescription("管理动作 "+(index+1));menu.setOnClickListener(v->stepMenu(owner,index,s));bar.addView(menu,new LinearLayout.LayoutParams(activity.dp(64),activity.dp(52)));box.addView(bar);
            addCommon(box,s);addAction(box,s);
            if(isBranch(s))renderBranch(box,s,depth);
            else addBlock(box,"添加下一动作",owner,index+1);
        }
        box.setBackground(ToolUi.fill(activity.surface,activity.dp(8),Color.TRANSPARENT,0));parent.addView(box,new LinearLayout.LayoutParams(-1,-2));
    }
    private void renderBranch(LinearLayout box,Step s,int depth){
        if(isIf(s)){
            boolean image=s.type==Step.Type.IF_IMAGE,variable=s.type==Step.Type.IF_VARIABLE;
            String hit=variable?"条件成立":image?"识别到图片":"识别到文字";
            String miss=variable?"条件不成立":image?"未识别到图片":"未识别到文字";
            branchBlock(box,hit+(s.clickMatch?"（先点击目标）":"")+" → …",s,depth,"执行后继续后面的动作",false);
            branchBlock(box,miss+" → …",s,depth,variable?"条件不成立时执行":"未识别到时执行",true);
        }else branchBlock(box,"每轮执行 → …",s,depth,"循环内依次执行",false);
    }
    private void branchBlock(LinearLayout box,String head,Step s,int depth,String hint,boolean otherwise){
        List<Step> list=otherwise?s.otherwise:s.children;
        LinearLayout block=new LinearLayout(activity);block.setOrientation(VERTICAL);block.setPadding(activity.dp(10),activity.dp(6),0,activity.dp(6));block.setBackground(ToolUi.fill(activity.surface,activity.dp(10),activity.accent,activity.dp(1)));
        labelInto(block,head+"（"+list.size()+" 个动作）");
        TextView hintView=new TextView(activity);hintView.setText(list.isEmpty()?"还没有动作":hint+"："+preview(list));ToolUi.text(hintView,list.isEmpty()?activity.muted:activity.ink,13);hintView.setPadding(0,0,0,activity.dp(4));block.addView(hintView);
        renderList(block,list,depth+1,otherwise?"否则动作":s.type==Step.Type.LOOP?"循环动作":"命中动作");
        String action;
        if(s.type==Step.Type.LOOP)action="添加循环动作";
        else if(s.type==Step.Type.IF_VARIABLE)action=otherwise?"添加条件不成立时的动作":"添加条件成立后的动作";
        else action=otherwise?"添加未识别到的动作":"添加识别到后的动作";
        Button add=smallButton(action);add.setOnClickListener(v->chooseType(t->addStep(list,t)));block.addView(add,new LinearLayout.LayoutParams(-1,activity.dp(48)));
        box.addView(block,new LinearLayout.LayoutParams(-1,-2));
    }
    /** First two actions plus a count, so the branch header alone says whether the branch is set up. */
    static String preview(List<Step> list){
        if(list==null||list.isEmpty())return"还没有动作";
        StringBuilder b=new StringBuilder();int shown=Math.min(2,list.size());
        for(int i=0;i<shown;i++){if(i>0)b.append(" → ");b.append(shortLabel(list.get(i)));}
        if(list.size()>shown)b.append(" 等 ").append(list.size()).append(" 个动作");
        return b.toString();
    }
    static String shortLabel(Step s){
        if(s==null||s.type==null)return"未选择动作";
        switch(s.type){
            case TAP:return"点击";case LONG_PRESS:return"长按";case SWIPE:return"滑动";case WAIT:return"等待";
            case WAIT_UNTIL:return"预约等待";case INPUT_TEXT:return"输入文本";case BACK:return"返回";case HOME:return"主页";
            case RECENTS:return"最近任务";case WAIT_TEXT:return(s.clickMatch?"等待并点击“":"等待文字“")+(s.text.isEmpty()?"文字":clip(s.text))+"”";
            case WAIT_IMAGE:return(s.clickMatch?"等待并点击图片":"等待图片");case OCR:return"识别文字";
            case IF_TEXT:return(s.clickMatch?"识别到并点击“":"如果文字“")+(s.text.isEmpty()?"文字":clip(s.text))+"”";
            case IF_IMAGE:return s.clickMatch?"识别到图片并点击":"如果图片";case LOOP:return"循环";
            case SET_VARIABLE:return"设置变量";case IF_VARIABLE:return"如果变量";default:return"停止";
        }
    }
    private static String clip(String value){return value.length()>12?value.substring(0,12)+"…":value;}
    /** Validate before hiding a field; an invalid raw value must never silently fall back to the old model. */
    private void toggle(Step s){
        if(!validateVisible())return;
        if(!expanded.remove(s.id))expanded.add(s.id);
        rebuild();
    }
    private void stepMenu(List<Step> owner,int index,Step s){
        new AlertDialog.Builder(activity).setTitle("动作 "+(index+1)).setItems(new String[]{"上移","下移","复制动作","删除"},(d,which)->{
            try{
                if(which!=3&&!validateVisible())return;
                if(which==0){if(index>0){owner.set(index,owner.set(index-1,s));rebuild();}}
                else if(which==1){if(index+1<owner.size()){owner.set(index,owner.set(index+1,s));rebuild();}}
                else if(which==2){owner.add(index+1,freshCopy(s));expanded.add(owner.get(index+1).id);rebuild();}
                else{owner.remove(index);rebuild();}
            }catch(RuntimeException ex){invalid=ex.getMessage();listener.changed();rebuild();}
        }).show();
    }
    /** Deep copy with brand-new ids; step ids are the storage identity, so they must not repeat. */
    private Step freshCopy(Step s){
        Step copy=s.copy();copy.id=UUID.randomUUID().toString();
        for(int i=0;i<copy.children.size();i++)copy.children.set(i,freshCopy(copy.children.get(i)));
        for(int i=0;i<copy.otherwise.size();i++)copy.otherwise.set(i,freshCopy(copy.otherwise.get(i)));
        return copy;
    }
    /** Simple actions first, branches under their own heading: the user never hunts through 18 enum rows. */
    static final Step.Type[] COMMON={Step.Type.TAP,Step.Type.WAIT_TEXT,Step.Type.WAIT_IMAGE,Step.Type.IF_TEXT,Step.Type.IF_IMAGE,Step.Type.INPUT_TEXT,Step.Type.LOOP,Step.Type.STOP};
    static final String[] GROUPS={"点击与手势","等待与识别文字","等待与识别图片","输入与系统按键","分支与循环","变量与收尾"};
    static final Step.Type[][] GROUP_TYPES={
        {Step.Type.TAP,Step.Type.LONG_PRESS,Step.Type.SWIPE},
        {Step.Type.WAIT_TEXT,Step.Type.IF_TEXT,Step.Type.OCR},
        {Step.Type.WAIT_IMAGE,Step.Type.IF_IMAGE},
        {Step.Type.WAIT,Step.Type.WAIT_UNTIL,Step.Type.INPUT_TEXT,Step.Type.BACK,Step.Type.HOME,Step.Type.RECENTS},
        {Step.Type.LOOP,Step.Type.IF_VARIABLE,Step.Type.STOP},
        {Step.Type.SET_VARIABLE}
    };
    private void chooseType(Consumer<Step.Type> add){
        String[] items={"常用动作…","全部动作（按类别）…"};
        new AlertDialog.Builder(activity).setTitle("添加什么动作").setItems(items,(d,which)->{
            if(which==0)pickType("常用动作",COMMON,add);else pickGroup(add);
        }).show();
    }
    private void pickGroup(Consumer<Step.Type> add){
        new AlertDialog.Builder(activity).setTitle("全部动作").setItems(GROUPS,(d,which)->{
            if(which<0||which>=GROUP_TYPES.length||GROUP_TYPES[which].length==0)return;
            pickType(GROUPS[which],GROUP_TYPES[which],add);
        }).show();
    }
    private void pickType(String title,Step.Type[] types,Consumer<Step.Type> add){
        if(types.length==1){add.accept(types[0]);rebuild();return;}
        String[] labels=new String[types.length];for(int i=0;i<types.length;i++)labels[i]=label(types[i])+(types[i]==Step.Type.IF_TEXT||types[i]==Step.Type.IF_IMAGE||types[i]==Step.Type.LOOP?"（带分支）":"");
        new AlertDialog.Builder(activity).setTitle(title).setItems(labels,(d,which)->{add.accept(types[which]);rebuild();}).show();
    }
    private String summary(Step s){
        StringBuilder b=new StringBuilder(s.type==null?"未选择动作":label(s.type));
        if(s.label!=null&&!s.label.isEmpty())b.append(" · ").append(s.label);
        if(needsPoint(s.type))b.append(" ").append(s.x).append(",").append(s.y);
        if(s.type==Step.Type.SWIPE)b.append(" → ").append(s.x2).append(",").append(s.y2);
        if(s.type==Step.Type.WAIT)b.append(" ").append(s.durationMs).append("毫秒");
        if(s.type==Step.Type.LOOP)b.append(" ×").append(s.count==0?"直到停止":String.valueOf(s.count)).append(" · ").append(s.intervalMs).append("毫秒");
        if(!s.text.isEmpty()&&(s.type==Step.Type.INPUT_TEXT||s.type==Step.Type.WAIT_TEXT||s.type==Step.Type.IF_TEXT))b.append(" “").append(s.text.length()>18?s.text.substring(0,18)+"…":s.text).append("”");
        if(!s.variable.isEmpty()&&(s.type==Step.Type.SET_VARIABLE||s.type==Step.Type.IF_VARIABLE||s.type==Step.Type.OCR))b.append(" ").append(s.variable);
        if(s.type==Step.Type.WAIT_IMAGE||s.type==Step.Type.IF_IMAGE)b.append(s.templateId.isEmpty()?" 未选模板":" 已选模板");
        if(s.clickMatch)b.append(" · 命中即点击");
        if(isBranch(s))b.append(" · 含 ").append(s.children.size()).append(" 个子动作");
        return b.toString();
    }
    private Spinner typeSpinner(Step s){Step.Type[] vals=Step.Type.values();String[] labels=new String[vals.length];for(int i=0;i<vals.length;i++)labels[i]=label(vals[i]);Spinner p=new Spinner(activity);p.setAdapter(choices(labels));p.setSelection(s.type==null?0:s.type.ordinal());p.setMinimumHeight(activity.dp(48));p.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){boolean first=true;public void onNothingSelected(AdapterView<?> x){}public void onItemSelected(AdapterView<?> x,View v,int pos,long id){if(!first||s.type==null){if(s.type!=vals[pos]){s.type=vals[pos];s.gesturePath=new int[0];if(!canClickMatch(vals[pos]))s.clickMatch=false;rawFields.keySet().removeIf(key->key.startsWith(s.id+"|"));}listener.changed();rebuild();}first=false;}});return p;}
    private String label(Step.Type t){switch(t){case TAP:return"点击";case LONG_PRESS:return"长按";case SWIPE:return"滑动";case WAIT:return"等待";case WAIT_UNTIL:return"预约等待";case INPUT_TEXT:return"输入文本";case BACK:return"返回";case HOME:return"主页";case RECENTS:return"最近任务";case WAIT_TEXT:return"等待文字";case WAIT_IMAGE:return"等待图片";case OCR:return"识别文字";case IF_TEXT:return"如果文字";case IF_IMAGE:return"如果图片";case LOOP:return"循环";case SET_VARIABLE:return"设置变量";case IF_VARIABLE:return"如果变量";default:return"停止";}}
    private void addCommon(LinearLayout b,Step s){
        addText(b,"动作说明",s.label,v->s.label=v);
        if(needsPoint(s.type)){
            LinearLayout c=new LinearLayout(activity);addInt(c,"x",s.x,v->{if(s.x!=v){s.x=v;s.gesturePath=new int[0];}});addInt(c,"y",s.y,v->{if(s.y!=v){s.y=v;s.gesturePath=new int[0];}});b.addView(c);
            LinearLayout actions=row();
            if(s.type!=Step.Type.SWIPE)actions.addView(pickButton("真实取点",s,"point"),new LinearLayout.LayoutParams(0,activity.dp(48),1));
            else actions.addView(pickButton("拖动取点",s,"swipe"),new LinearLayout.LayoutParams(0,activity.dp(48),1));
            b.addView(actions);
            b.addView(check("后续动作使用最近匹配位置",s.useLastMatch,v->s.useLastMatch=v));
        }
        if(s.type==Step.Type.SWIPE){LinearLayout c=new LinearLayout(activity);addInt(c,"x2",s.x2,v->{if(s.x2!=v){s.x2=v;s.gesturePath=new int[0];}});addInt(c,"y2",s.y2,v->{if(s.y2!=v){s.y2=v;s.gesturePath=new int[0];}});b.addView(c);}
        if(needsRegion(s.type)){
            addText(b,"区域 left,top,right,bottom",s.left+","+s.top+","+s.right+","+s.bottom,v->{int[] a=parse(v,4);if(a!=null){s.left=a[0];s.top=a[1];s.right=a[2];s.bottom=a[3];}});
            b.addView(pickButton("框选区域",s,"region"),new LinearLayout.LayoutParams(-1,activity.dp(48)));
        }
    }
    private Button pickButton(String text,Step s,String mode){Button b=smallButton(text);b.setOnClickListener(v->{try{listener.requestPick(s,mode);}catch(RuntimeException ex){invalid=ex.getMessage();listener.changed();}});return b;}
    private void addAction(LinearLayout b,Step s){Step.Type t=s.type==null?Step.Type.TAP:s.type;
        if(t==Step.Type.LONG_PRESS||t==Step.Type.SWIPE||t==Step.Type.WAIT)addLong(b,"持续时间（毫秒）",s.durationMs,v->s.durationMs=v);
        if(t==Step.Type.WAIT_UNTIL)addSchedule(b,s);
        if(t==Step.Type.INPUT_TEXT)addText(b,"输入文本（支持变量）",s.text,v->s.text=v);
        if(t==Step.Type.SET_VARIABLE){addText(b,"变量名",s.variable,v->s.variable=v);addText(b,"变量值",s.value,v->s.value=v);}
        if(t==Step.Type.WAIT_TEXT||t==Step.Type.WAIT_IMAGE||t==Step.Type.OCR||t==Step.Type.IF_TEXT||t==Step.Type.IF_IMAGE||t==Step.Type.LOOP){
            CheckBox click=check(CLICK_MATCH,s.clickMatch,v->s.clickMatch=v);
            if(canClickMatch(t))b.addView(click);
            addLong(b,"超时（毫秒）",s.timeoutMs,v->s.timeoutMs=v);addLong(b,"识别/循环间隔（毫秒）",s.intervalMs,v->s.intervalMs=v);
        }
        if(t==Step.Type.WAIT_TEXT||t==Step.Type.IF_TEXT||t==Step.Type.OCR){
            addText(b,"文字条件/识别文本",s.text,v->s.text=v);addText(b,"识别结果变量（可选）",s.variable,v->s.variable=v);
            addSelect(b,"文字来源",new String[]{"本地OCR","节点文本"},s.textSource,v->s.textSource="节点文本".equals(v)?"nodes":"ocr");
            addSelect(b,"匹配方式",new String[]{"包含","完全相同","正则"},s.matchMode,v->s.matchMode="包含".equals(v)?"contains":"完全相同".equals(v)?"exact":"regex");
        }
        if(t==Step.Type.WAIT_IMAGE||t==Step.Type.IF_IMAGE){
            addText(b,"模板ID",s.templateId,v->s.templateId=v);
            LinearLayout takes=row();takes.addView(pickButton("截图取模板",s,"template"),new LinearLayout.LayoutParams(0,activity.dp(48),1));
            Button choose=smallButton("从图片文件选择");choose.setOnClickListener(v->listener.chooseTemplate(s));takes.addView(choose,new LinearLayout.LayoutParams(0,activity.dp(48),1));b.addView(takes);
            addText(b,"匹配阈值（0..1）",String.valueOf(s.threshold),v->{try{double parsed=Double.parseDouble(v);if(Double.isNaN(parsed)||Double.isInfinite(parsed)||parsed<0||parsed>1)throw new NumberFormatException();s.threshold=parsed;invalid=null;}catch(Exception e){invalid="匹配阈值必须是数字";}});
        }
        if(t==Step.Type.IF_VARIABLE){addText(b,"变量名",s.variable,v->s.variable=v);addSelect(b,"比较符",new String[]{"等于","不等于","包含","大于","大于或等于","小于","小于或等于","正则"},s.operator,v->s.operator=op(v));addText(b,"比较值",s.value,v->s.value=v);}
        if(t==Step.Type.LOOP)addIntField(b,"重复次数（0=直到停止）",s.count,v->s.count=v);
    }
    private void addSchedule(LinearLayout b,Step s){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault());f.setLenient(false);String initial=s.atEpochMs==0?"":f.format(new Date(s.atEpochMs));addText(b,"预约时间（本地 yyyy-MM-dd HH:mm，留空=不预约）",initial,v->{if(v.trim().isEmpty()){s.atEpochMs=0;invalid=null;return;}try{s.atEpochMs=f.parse(v.trim()).getTime();invalid=null;}catch(Exception e){invalid="预约时间格式应为 yyyy-MM-dd HH:mm";}});}
    private String op(String label){switch(label){case "等于":return "equals";case "不等于":return "notEquals";case "包含":return "contains";case "大于":return "greater";case "大于或等于":return "greaterOrEqual";case "小于":return "less";case "小于或等于":return "lessOrEqual";default:return "regex";}}
    /** clickMatch is only meaningful where the contract runs it: WAIT/IF over text or image. */
    static boolean canClickMatch(Step.Type t){return t==Step.Type.WAIT_TEXT||t==Step.Type.WAIT_IMAGE||t==Step.Type.IF_TEXT||t==Step.Type.IF_IMAGE;}
    private boolean isIf(Step s){return s.type==Step.Type.IF_TEXT||s.type==Step.Type.IF_IMAGE||s.type==Step.Type.IF_VARIABLE;}private boolean isBranch(Step s){return isIf(s)||s.type==Step.Type.LOOP;}private boolean needsPoint(Step.Type t){return t==Step.Type.TAP||t==Step.Type.LONG_PRESS||t==Step.Type.SWIPE;}private boolean needsRegion(Step.Type t){return t==Step.Type.WAIT_TEXT||t==Step.Type.WAIT_IMAGE||t==Step.Type.OCR||t==Step.Type.IF_TEXT||t==Step.Type.IF_IMAGE;}
    private CheckBox check(String text,boolean value,java.util.function.Consumer<Boolean> set){CheckBox c=new CheckBox(activity);c.setText(text);c.setTextColor(activity.ink);c.setChecked(value);c.setMinHeight(activity.dp(48));c.setOnCheckedChangeListener((b,v)->set.accept(v));return c;}
    private void addSelect(LinearLayout parent,String title,String[] labels,String current,Consumer<String> set){
        labelInto(parent,title);Spinner spinner=new Spinner(activity);spinner.setAdapter(choices(labels));spinner.setMinimumHeight(activity.dp(48));
        String shown=current;
        if(current!=null)switch(current){case "nodes":shown="节点文本";break;case "ocr":shown="本地OCR";break;case "contains":shown="包含";break;case "exact":shown="完全相同";break;case "regex":shown="正则";break;case "equals":case "==":shown="等于";break;case "notEquals":case "!=":shown="不等于";break;case "greater":case ">":shown="大于";break;case "greaterOrEqual":case ">=":shown="大于或等于";break;case "less":case "<":shown="小于";break;case "lessOrEqual":case "<=":shown="小于或等于";break;default:break;}
        int selected=0;for(int i=0;i<labels.length;i++)if(labels[i].equals(shown))selected=i;
        final boolean[] first={true};spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> x){}public void onItemSelected(AdapterView<?> x,View v,int pos,long id){if(first[0]){first[0]=false;return;}set.accept(labels[pos]);}});spinner.setSelection(selected,false);parent.addView(spinner);
    }
    private ArrayAdapter<String> choices(String[] labels){return new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_item,labels){
        private View style(View view){if(view instanceof TextView)ToolUi.text((TextView)view,activity.ink,16);view.setBackgroundColor(activity.surface);view.setMinimumHeight(activity.dp(48));return view;}
        @Override public View getView(int position,View view,android.view.ViewGroup parent){return style(super.getView(position,view,parent));}
        @Override public View getDropDownView(int position,View view,android.view.ViewGroup parent){return style(super.getDropDownView(position,view,parent));}
    };}
    private EditText addText(LinearLayout parent,String title,String value,Consumer<String> set){
        labelInto(parent,title);final String key=fieldOwner+"|"+title;String shown=rawFields.containsKey(key)?rawFields.get(key):(value==null?"":value);rawFields.put(key,shown);
        EditText input=new EditText(activity);input.setText(shown);input.setTextColor(activity.ink);input.setMinHeight(activity.dp(48));input.setSingleLine(true);input.setBackground(ToolUi.ripple(activity,activity.surface,activity.dp(8),Color.TRANSPARENT,0));
        Runnable reader=()->{invalid=null;set.accept(input.getText().toString());};fieldReaders.add(reader);
        input.addTextChangedListener(watch(text->{rawFields.put(key,text);try{reader.run();}catch(RuntimeException ex){invalid=ex.getMessage();}input.setError(invalid);}));parent.addView(input);return input;
    }
    private void addInt(LinearLayout p,String title,int value,IntConsumer set){LinearLayout column=new LinearLayout(activity);column.setOrientation(VERTICAL);column.setPadding(0,0,activity.dp(8),0);addIntField(column,title,value,set);p.addView(column,new LinearLayout.LayoutParams(0,-2,1));}
    private void addIntField(LinearLayout parent,String title,int value,IntConsumer set){EditText input=addText(parent,title,String.valueOf(value),text->{try{int parsed=Integer.parseInt(text);if(parsed<0)throw new NumberFormatException();set.accept(parsed);}catch(RuntimeException ex){invalid=title+"必须是非负整数";}});input.setInputType(InputType.TYPE_CLASS_NUMBER);}
    private void addLong(LinearLayout parent,String title,long value,LongConsumer set){EditText input=addText(parent,title,String.valueOf(value),text->{try{long parsed=Long.parseLong(text);if(parsed<0||parsed>7L*24*3600*1000)throw new NumberFormatException();set.accept(parsed);}catch(RuntimeException ex){invalid=title+"必须是有效非负数字";}});input.setInputType(InputType.TYPE_CLASS_NUMBER);}
    /** Adding the next action in place: the editor never dead-ends at the end of a list. */
    private void addBlock(LinearLayout parent,String text,List<Step> list,int at){
        Button add=new Button(activity);add.setText(text);add.setAllCaps(false);ToolUi.text(add,activity.surface,15);ToolUi.touch(add,activity.dp(48));add.setBackground(ToolUi.ripple(activity,activity.accent,activity.dp(12),activity.accent,0));
        add.setOnClickListener(v->chooseType(type->addStepAt(list,at,type)));
        parent.addView(add,new LinearLayout.LayoutParams(-1,activity.dp(48)));
    }
    private TextWatcher watch(Consumer<String> c){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int d){}public void onTextChanged(CharSequence s,int a,int b,int d){c.accept(s.toString());}public void afterTextChanged(Editable e){}};}
    private TextView label(String s){TextView t=new TextView(activity);t.setText(s);ToolUi.text(t,activity.muted,13);t.setPadding(0,activity.dp(8),0,activity.dp(3));return t;}private void labelInto(LinearLayout p,String s){p.addView(label(s));}
    private Button small(String s){Button b=new Button(activity);b.setText(s);b.setAllCaps(false);ToolUi.text(b,activity.accent,14);ToolUi.touch(b,activity.dp(44));b.setBackground(ToolUi.ripple(activity,activity.surface,activity.dp(8),Color.TRANSPARENT,0));return b;}
    private Button smallButton(String s){Button b=small(s);b.setPadding(0,0,0,0);return b;}
    private LinearLayout row(){LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setOrientation(HORIZONTAL);return row;}
    private int[] parse(String s,int n){String[] a=s.split(",");if(a.length!=n){invalid="区域需要四个整数";return null;}int[] out=new int[n];try{for(int i=0;i<n;i++)out[i]=Integer.parseInt(a[i].trim());invalid=null;return out;}catch(Exception e){invalid="区域必须是整数";return null;}}
}
