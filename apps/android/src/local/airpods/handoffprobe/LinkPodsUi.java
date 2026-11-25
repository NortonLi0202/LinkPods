package local.airpods.handoffprobe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptive native UI: One UI rounded cards with a quiet, minimal hierarchy.
 * The root tree is built once; navigation only swaps the content column and the sidebar selection.
 * The activity remains the owner of service, permission and Root actions.
 */
final class LinkPodsUi {
    interface Actions {
        void openBluetooth(); void requestNearby(); void openMediaAccess(); void requestNotifications();
        void refreshDevices(); void connectEarbuds(); void enableHandoff(); void disableHandoff(); void manualHandoff(); void stopConnection(); void stopHandoff(); void checkEnvironment(); void prepareBridge();
        boolean setSetting(int id,int value);
        String deviceName(); String connectionStatus();
        void runPrimaryAction(StatusSummary.PrimaryAction action);
    }

    static final String HOME="设备", CONNECTION="连接与诊断", SETTINGS="设置与日志";
    private static final String VERSION_LABEL="LinkPods v1.1 (build 102)";

    private final Activity activity;
    private final Actions actions;
    private final Spinner picker;
    final TextView environmentStatus,logOutput;
    private final AudioManager audio;
    private final UiTheme t;
    private final boolean tablet;
    private final int screenWidthDp;

    // Persistent shell.
    private LinearLayout root,body;
    private ScrollView scroll;
    private TextView title,backButton,trailingButton,sidebarDevice;
    private final Map<String,TextView> navRows=new LinkedHashMap<>();

    // Page-bound views; reset on every page render.
    private LinearLayout artwork;
    private LinearLayout modelPlate;
    private TextView plateTitle,plateCaption,heroName,heroModel,batteryHint,feedback;
    private BatteryPill leftPill,rightPill,casePill;
    private View summaryDot;
    private TextView summaryBadge,summaryHeadline,summaryDetail,summaryAction;
    private TextView modeChip,modeHint;
    private PodControls.NoiseSelector noise;
    private PodControls.VolumeSlider volume;
    private TextView volumeValue;
    private final ArrayList<SettingRow> settings=new ArrayList<>();
    private TextView handoffShortcut,autoStartValue;
    private TextView diagAacp,diagHandoff,diagA2dp,diagHeadset,diagRoute,diagCapability,diagBridge,diagMedia;
    private TextView permBluetooth,permMedia,permNotifications;

    // Observed state, re-applied after each render.
    private EarbudSnapshot snapshot;
    private SessionStatus sessionStatus;
    private SamsungAudioSnapshot audioSnapshot;
    private ConnectionPolicy.Snapshot capabilities;
    private boolean mediaGranted;
    private Boolean btFlag,mediaFlag,notifFlag,autoStartFlag,autoStartSuppressedFlag;
    private StatusSummary currentSummary;
    private Integer lastLeft,lastRight,lastCase;
    private String target="",page=HOME;
    private final ArrayList<String> history=new ArrayList<>();

    LinkPodsUi(Activity a,Spinner spinner,Actions actions,String restoredPage) {
        activity=a;picker=spinner;this.actions=actions;audio=a.getSystemService(AudioManager.class);
        t=UiTheme.of(a);
        screenWidthDp=a.getResources().getConfiguration().screenWidthDp;
        // The 720dp split accommodates the target tablet's 738dp portrait window.
        tablet=screenWidthDp>=720;
        environmentStatus=text("Root 尚未检测；直连无需此项",13,t.muted);
        environmentStatus.setLineSpacing(dp(2),1);
        logOutput=text("",12,t.muted);
        logOutput.setTextIsSelectable(true);
        logOutput.setTypeface(Typeface.MONOSPACE);
        logOutput.setLineSpacing(dp(2),1);
        logOutput.setGravity(Gravity.TOP|Gravity.START);
        page=normalize(restoredPage);
        buildShell();
        render();
    }

    int dp(float value){return t.dp(value);}
    String page(){return page;}

    private static String normalize(String name){
        return CONNECTION.equals(name)||SETTINGS.equals(name)?name:HOME;
    }

    void show(String destination){
        String next=normalize(destination);
        if(page.equals(next))return;
        history.add(page);page=next;render();
    }

    boolean back(){
        if(HOME.equals(page))return false;
        page=history.isEmpty()?HOME:history.remove(history.size()-1);
        render();
        return true;
    }

    // ---------------------------------------------------------------- primitives

    private TextView text(String value,float sp,int color){
        TextView v=new TextView(activity);v.setText(value);v.setTextSize(sp);v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);v.setFontFeatureSettings("kern");v.setIncludeFontPadding(true);
        return v;
    }
    private TextView strong(String value,float sp,int color){TextView v=text(value,sp,color);v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    private TextView heavy(String value,float sp,int color){TextView v=text(value,sp,color);v.setTypeface(null,Typeface.BOLD);return v;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void detach(View v){if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1);}
    private GradientDrawable oval(int color){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(color);return d;}

    private LinearLayout card(){
        LinearLayout c=column();
        c.setBackground(t.rounded(t.surface,UiTheme.CARD_RADIUS_DP));
        c.setClipToOutline(true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);
        body.addView(c,p);
        return c;
    }
    private void section(String value){
        TextView v=strong(value,13,t.muted);v.setPadding(dp(14),dp(12),dp(14),dp(8));v.setAccessibilityHeading(true);body.addView(v);
    }
    private void help(String value){
        TextView v=text(value,12,t.muted);v.setPadding(dp(14),0,dp(14),dp(18));v.setLineSpacing(dp(3),1);body.addView(v);
    }
    private void divider(LinearLayout c){
        if(c.getChildCount()==0)return;
        View v=new View(activity);v.setBackgroundColor(t.divider);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,Math.max(1,dp(.6f)));p.leftMargin=dp(20);p.rightMargin=dp(20);
        c.addView(v,p);
    }
    private LinearLayout listRow(LinearLayout c){
        divider(c);
        LinearLayout r=horizontal();r.setPadding(dp(20),dp(12),dp(16),dp(12));r.setMinimumHeight(dp(60));
        c.addView(r,new LinearLayout.LayoutParams(-1,-2));
        return r;
    }
    private void tap(View v,Runnable action){t.makeClickable(v,Color.TRANSPARENT,0,action);}
    private TextView badge(String value){
        TextView b=strong(value,12,t.muted);b.setGravity(Gravity.CENTER);b.setPadding(dp(10),dp(3),dp(10),dp(3));b.setMaxLines(1);
        b.setBackground(t.rounded(t.surfaceSubtle,UiTheme.PILL_RADIUS_DP));
        return b;
    }
    /** tone: 0 ok, 1 attention, 2 danger, 3 accent, otherwise neutral. */
    private void tone(TextView b,int tone,String value){
        if(b==null)return;
        int fg=tone==0?t.ok:tone==1?t.warn:tone==2?t.danger:tone==3?t.accent:t.muted;
        int bg=tone==0?t.okTint:tone==1?t.warnTint:tone==2?t.dangerTint:tone==3?t.accentTint:t.surfaceSubtle;
        b.setText(value);b.setTextColor(fg);b.setBackground(t.rounded(bg,UiTheme.PILL_RADIUS_DP));
    }
    private TextView[] titled(LinearLayout r,String name,String subtitle,int color){
        LinearLayout labels=column();
        TextView main=text(name,16,color);labels.addView(main);
        TextView sub=text(subtitle==null?"":subtitle,12,t.muted);sub.setPadding(0,dp(2),0,0);sub.setLineSpacing(dp(1),1);
        sub.setVisibility(subtitle==null||subtitle.isEmpty()?View.GONE:View.VISIBLE);labels.addView(sub);
        r.addView(labels,weight());
        return new TextView[]{main,sub};
    }
    private void actionRow(LinearLayout c,String name,String subtitle,int color,Runnable task){
        LinearLayout r=listRow(c);titled(r,name,subtitle,color);tap(r,task);
    }
    private TextView navRow(LinearLayout c,String name,String value,Runnable task){
        LinearLayout r=listRow(c);titled(r,name,null,t.ink);
        TextView v=text(value,14,t.muted);v.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);v.setMaxWidth(dp(190));v.setMaxLines(1);v.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(v);
        TextView chevron=text("›",22,t.muted);chevron.setPadding(dp(10),0,0,dp(2));r.addView(chevron);
        tap(r,task);
        return v;
    }
    private TextView keyValue(LinearLayout c,String key){
        divider(c);
        LinearLayout r=horizontal();r.setGravity(Gravity.TOP);r.setPadding(dp(20),dp(12),dp(20),dp(12));
        TextView k=text(key,13,t.muted);k.setGravity(Gravity.START|Gravity.TOP);
        r.addView(k,new LinearLayout.LayoutParams(dp(screenWidthDp<400?112:132),-2));
        TextView v=text("--",14,t.ink);v.setGravity(Gravity.END|Gravity.TOP);v.setLineSpacing(dp(2),1);v.setTextIsSelectable(true);
        r.addView(v,weight());
        c.addView(r,new LinearLayout.LayoutParams(-1,-2));
        return v;
    }

    // ---------------------------------------------------------------- shell

    private void buildShell(){
        root=horizontal();root.setGravity(Gravity.NO_GRAVITY);root.setBackgroundColor(t.bg);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
        activity.getWindow().setStatusBarColor(t.bg);activity.getWindow().setNavigationBarColor(t.bg);
        WindowInsetsController bars=activity.getWindow().getDecorView().getWindowInsetsController();
        int lightBars=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        if(bars!=null)bars.setSystemBarsAppearance(t.dark?0:lightBars,lightBars);
        if(tablet)buildSidebar(screenWidthDp>=840?272:228);

        LinearLayout main=column();root.addView(main,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout bar=horizontal();bar.setPadding(dp(tablet?20:8),0,dp(12),0);main.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));
        backButton=text("‹",30,t.accent);backButton.setGravity(Gravity.CENTER);backButton.setPadding(0,0,0,dp(4));backButton.setContentDescription("返回");
        t.makeClickable(backButton,Color.TRANSPARENT,UiTheme.PILL_RADIUS_DP,this::back);
        bar.addView(backButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        title=heavy("",tablet?26:22,t.ink);title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.END);title.setAccessibilityHeading(true);
        title.setPadding(dp(8),0,dp(8),0);
        bar.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        trailingButton=strong("设置",15,t.accent);trailingButton.setGravity(Gravity.CENTER);trailingButton.setPadding(dp(14),0,dp(14),0);
        trailingButton.setContentDescription("打开设置与日志");
        t.makeClickable(trailingButton,t.accentTint,UiTheme.PILL_RADIUS_DP,()->show(SETTINGS));
        bar.addView(trailingButton,new LinearLayout.LayoutParams(-2,dp(38)));

        scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        main.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout frame=new FrameLayout(activity);scroll.addView(frame,new ScrollView.LayoutParams(-1,-2));
        final int maxWidth=dp(tablet?680:620);
        body=new LinearLayout(activity){@Override protected void onMeasure(int ws,int hs){
            int available=View.MeasureSpec.getSize(ws);
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(Math.min(maxWidth,available),View.MeasureSpec.EXACTLY),hs);}};
        body.setOrientation(LinearLayout.VERTICAL);
        int side=dp(screenWidthDp<400?14:tablet?28:20);
        body.setPadding(side,dp(4),side,dp(32));
        frame.addView(body,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        activity.setContentView(root);root.requestApplyInsets();
    }

    private void buildSidebar(int widthDp){
        LinearLayout sidebar=column();sidebar.setPadding(dp(14),dp(24),dp(14),dp(20));
        ScrollView sideScroll=new ScrollView(activity);sideScroll.setFillViewport(true);sideScroll.setVerticalScrollBarEnabled(false);
        sideScroll.setBackgroundColor(t.sidebarBg);sideScroll.addView(sidebar);
        root.addView(sideScroll,new LinearLayout.LayoutParams(dp(widthDp),-1));
        TextView brand=heavy("LinkPods",28,t.ink);brand.setPadding(dp(12),0,0,dp(4));sidebar.addView(brand);
        TextView tagline=text("耳机控制与自动交接",13,t.muted);tagline.setPadding(dp(12),0,0,dp(24));sidebar.addView(tagline);
        sidebarDevice=navItem(sidebar,"◉",HOME,actions.deviceName());
        navItem(sidebar,"⇄",CONNECTION,"交接操作与音频诊断");
        navItem(sidebar,"⚙",SETTINGS,"目标耳机、权限与运行记录");
        View spacer=new View(activity);sidebar.addView(spacer,new LinearLayout.LayoutParams(-1,0,1));
        TextView version=text(VERSION_LABEL,11,t.muted);version.setPadding(dp(12),dp(20),0,0);sidebar.addView(version);
    }

    private TextView navItem(LinearLayout sidebar,String glyph,String destination,String subtitle){
        LinearLayout r=horizontal();r.setPadding(dp(12),dp(10),dp(12),dp(10));r.setMinimumHeight(dp(56));
        TextView icon=text(glyph,17,t.muted);icon.setGravity(Gravity.CENTER);r.addView(icon,new LinearLayout.LayoutParams(dp(28),-2));
        LinearLayout labels=column();labels.setPadding(dp(10),0,0,0);
        TextView name=strong(destination,15,t.ink);labels.addView(name);
        TextView sub=text(subtitle,12,t.muted);sub.setMaxLines(1);sub.setEllipsize(TextUtils.TruncateAt.END);labels.addView(sub);
        r.addView(labels,weight());
        r.setTag(new TextView[]{icon,name,sub});
        r.setOnClickListener(v->show(destination));r.setClickable(true);r.setFocusable(true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(4);sidebar.addView(r,p);
        navRows.put(destination,name);
        return sub;
    }

    private void updateChrome(){
        boolean home=HOME.equals(page);
        title.setText(home?(tablet?"设备":"LinkPods"):page);
        backButton.setVisibility(!home&&!tablet?View.VISIBLE:View.GONE);
        trailingButton.setVisibility(home&&!tablet?View.VISIBLE:View.GONE);
        title.setPadding(dp(!home&&!tablet?0:8),0,dp(8),0);
        for(Map.Entry<String,TextView> e:navRows.entrySet()){
            View row=(View)e.getValue().getParent().getParent();
            TextView[] parts=(TextView[])row.getTag();
            boolean selected=e.getKey().equals(page);
            row.setSelected(selected);
            t.makeClickable(row,selected?t.accentTint:Color.TRANSPARENT,18,()->show(e.getKey()));
            parts[0].setTextColor(selected?t.accent:t.muted);
            parts[1].setTextColor(selected?t.accent:t.ink);
        }
    }

    private void render(){
        body.removeAllViews();
        settings.clear();
        artwork=null;modelPlate=null;plateTitle=null;plateCaption=null;heroName=null;heroModel=null;batteryHint=null;feedback=null;
        leftPill=null;rightPill=null;casePill=null;
        summaryDot=null;summaryBadge=null;summaryHeadline=null;summaryDetail=null;summaryAction=null;
        modeChip=null;modeHint=null;noise=null;volume=null;volumeValue=null;handoffShortcut=null;autoStartValue=null;
        diagAacp=null;diagHandoff=null;diagA2dp=null;diagHeadset=null;diagRoute=null;diagCapability=null;diagBridge=null;diagMedia=null;
        permBluetooth=null;permMedia=null;permNotifications=null;
        if(CONNECTION.equals(page))connectionPage();
        else if(SETTINGS.equals(page))settingsPage();
        else homePage();
        updateChrome();
        scroll.scrollTo(0,0);
        if(snapshot!=null)bind(snapshot,target,sessionStatus);else updateLocalVolume();
        bindAudio(audioSnapshot);
        bindCapabilities(capabilities,mediaGranted);
        bindFlagsToViews();
        if(currentSummary!=null)bindSummary(currentSummary);
        else if(handoffShortcut!=null)handoffShortcut.setText(handoffBrief());
    }

    // ---------------------------------------------------------------- HOME

    private void homePage(){
        heroCard();
        summaryCard();
        noiseCard();
        volumeCard();
        section("耳机功能");
        LinearLayout features=card();
        switchRow(features,"个性化音量",EarbudCommands.PERSONALIZED_VOLUME);
        switchRow(features,"对话感知",EarbudCommands.CONVERSATION_AWARENESS);
        switchRow(features,"自动入耳检测",EarbudCommands.EAR_DETECTION);
        switchRow(features,"单耳降噪",EarbudCommands.ONE_BUD_ANC);
        switchRow(features,"音量滑动",EarbudCommands.VOLUME_SWIPE);
        help("耳机回报当前设置后才可更改；此型号不支持或尚未读取的功能保持不可用。");
        LinearLayout shortcuts=card();
        handoffShortcut=navRow(shortcuts,"自动交接与连接","",()->show(CONNECTION));
        navRow(shortcuts,"设置与日志","",()->show(SETTINGS));
        feedback=text("",12,t.muted);feedback.setGravity(Gravity.CENTER);feedback.setPadding(dp(14),dp(2),dp(14),dp(8));body.addView(feedback);
    }

    private void heroCard(){
        LinearLayout c=card();c.setPadding(dp(20),dp(22),dp(20),dp(20));
        artwork=horizontal();artwork.setGravity(Gravity.CENTER);
        artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for(String name:new String[]{"lp_earbuds","lp_case"}){
            ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setAdjustViewBounds(true);
            int id=activity.getResources().getIdentifier(name,"drawable",activity.getPackageName());
            if(id!=0)image.setImageResource(id);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(6),0,dp(6),0);
            artwork.addView(image,p);
        }
        artwork.setVisibility(View.GONE);
        c.addView(artwork,new LinearLayout.LayoutParams(-1,dp(tablet?190:164)));

        modelPlate=column();modelPlate.setGravity(Gravity.CENTER);modelPlate.setPadding(dp(16),dp(18),dp(16),dp(18));
        modelPlate.setBackground(t.rounded(t.surfaceSubtle,22));
        TextView glyph=text("◖ ◗",22,t.muted);glyph.setGravity(Gravity.CENTER);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);modelPlate.addView(glyph);
        plateTitle=heavy("型号尚未识别",20,t.ink);plateTitle.setGravity(Gravity.CENTER);plateTitle.setPadding(0,dp(6),0,0);modelPlate.addView(plateTitle);
        plateCaption=text("等待耳机回报型号信息",12,t.muted);plateCaption.setGravity(Gravity.CENTER);plateCaption.setPadding(0,dp(4),0,0);modelPlate.addView(plateCaption);
        c.addView(modelPlate,new LinearLayout.LayoutParams(-1,dp(132)));

        heroName=heavy(actions.deviceName(),22,t.ink);heroName.setGravity(Gravity.CENTER);heroName.setPadding(0,dp(16),0,0);
        heroName.setMaxLines(2);heroName.setEllipsize(TextUtils.TruncateAt.END);c.addView(heroName,new LinearLayout.LayoutParams(-1,-2));
        heroModel=text("型号尚未识别",13,t.muted);heroModel.setGravity(Gravity.CENTER);heroModel.setPadding(0,dp(4),0,dp(16));
        c.addView(heroModel,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout pills=horizontal();pills.setGravity(Gravity.TOP);
        leftPill=new BatteryPill("左耳");rightPill=new BatteryPill("右耳");casePill=new BatteryPill("充电盒");
        addPill(pills,leftPill,0);addPill(pills,rightPill,dp(8));addPill(pills,casePill,dp(8));
        c.addView(pills,new LinearLayout.LayoutParams(-1,-2));
        batteryHint=text("",12,t.muted);batteryHint.setGravity(Gravity.CENTER);batteryHint.setPadding(0,dp(10),0,0);batteryHint.setVisibility(View.GONE);
        c.addView(batteryHint,new LinearLayout.LayoutParams(-1,-2));
    }
    private void addPill(LinearLayout row,BatteryPill pill,int leftMargin){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=leftMargin;row.addView(pill.box,p);
    }

    private void summaryCard(){
        LinearLayout c=card();c.setPadding(dp(20),dp(18),dp(20),dp(18));
        LinearLayout header=horizontal();
        summaryDot=new View(activity);summaryDot.setBackground(oval(t.muted));
        LinearLayout.LayoutParams dot=new LinearLayout.LayoutParams(dp(10),dp(10));dot.rightMargin=dp(10);header.addView(summaryDot,dot);
        summaryBadge=badge("状态");header.addView(summaryBadge);
        c.addView(header);
        summaryHeadline=heavy("正在检查状态",18,t.ink);summaryHeadline.setPadding(0,dp(12),0,0);summaryHeadline.setLineSpacing(dp(2),1);c.addView(summaryHeadline);
        summaryDetail=text("",13,t.muted);summaryDetail.setPadding(0,dp(6),0,0);summaryDetail.setLineSpacing(dp(3),1);summaryDetail.setVisibility(View.GONE);c.addView(summaryDetail);
        summaryAction=strong("",15,Color.WHITE);summaryAction.setGravity(Gravity.CENTER);summaryAction.setVisibility(View.GONE);
        t.makeClickable(summaryAction,t.accent,UiTheme.PILL_RADIUS_DP,()->{if(currentSummary!=null&&currentSummary.hasPrimaryAction())actions.runPrimaryAction(currentSummary.primaryAction);});
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(46));ap.topMargin=dp(16);c.addView(summaryAction,ap);
    }

    private void noiseCard(){
        LinearLayout c=card();c.setPadding(dp(20),dp(18),dp(20),dp(16));
        LinearLayout header=horizontal();
        header.addView(heavy("噪声控制",17,t.ink),weight());
        modeChip=badge("尚未读取");header.addView(modeChip);
        c.addView(header);
        noise=new PodControls.NoiseSelector(activity,t.dark,value->submit(EarbudCommands.LISTENING_MODE,value));
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,dp(88));np.topMargin=dp(14);c.addView(noise,np);
        modeHint=text("",12,t.muted);modeHint.setPadding(dp(2),dp(4),dp(2),0);modeHint.setLineSpacing(dp(2),1);modeHint.setVisibility(View.GONE);c.addView(modeHint);
    }

    private void volumeCard(){
        LinearLayout c=card();c.setPadding(dp(20),dp(16),dp(20),dp(14));
        LinearLayout header=horizontal();
        header.addView(heavy("媒体音量",17,t.ink),weight());
        volumeValue=strong("",15,t.accent);header.addView(volumeValue);
        c.addView(header);
        LinearLayout sliderRow=horizontal();
        TextView quiet=text("−",20,t.muted);quiet.setGravity(Gravity.CENTER);sliderRow.addView(quiet,new LinearLayout.LayoutParams(dp(24),dp(48)));
        volume=new PodControls.VolumeSlider(activity,t.dark,value->{
            if(audio==null)return;
            try{audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);updateLocalVolume();}
            catch(SecurityException e){message("系统未允许调节媒体音量");}
        });
        sliderRow.addView(volume,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView loud=text("+",20,t.muted);loud.setGravity(Gravity.CENTER);sliderRow.addView(loud,new LinearLayout.LayoutParams(dp(24),dp(48)));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(6);c.addView(sliderRow,sp);
        TextView local=text("此 Android 设备的媒体音量",12,t.muted);local.setPadding(dp(2),dp(2),0,0);c.addView(local);
    }

    private void switchRow(LinearLayout c,String label,int id){
        LinearLayout r=listRow(c);
        TextView[] parts=titled(r,label,"尚未读取",t.ink);
        parts[1].setVisibility(View.VISIBLE);
        PodControls.GlassSwitch toggle=new PodControls.GlassSwitch(activity,t.dark,label,v->submit(id,EarbudCommands.booleanValue(v==1)));
        toggle.bind(null,false);
        r.addView(toggle,new LinearLayout.LayoutParams(dp(68),dp(48)));
        settings.add(new SettingRow(id,toggle,parts[1]));
    }

    // ---------------------------------------------------------------- CONNECTION

    private void connectionPage(){
        summaryCard();
        section("交接操作");
        LinearLayout ops=card();
        actionRow(ops,"连接耳机（仅控制）","直接建立耳机控制通道，不接管音频",t.accent,actions::connectEarbuds);
        actionRow(ops,"启用自动交接","根据本机播放状态协调耳机交接",t.accent,actions::enableHandoff);
        actionRow(ops,"手动切换到此耳机","立即请求把耳机音频切到本机",t.accent,actions::manualHandoff);
        actionRow(ops,"关闭自动交接（保留控制）",null,t.accent,actions::disableHandoff);
        actionRow(ops,"停止连接",null,t.danger,actions::stopConnection);
        LinearLayout auto=listRow(ops);titled(auto,"蓝牙连接时自动启停","随「启用自动交接」开启；关闭自动交接后关闭，停止连接仅本次暂停",t.ink);
        autoStartValue=badge("未检查");auto.addView(autoStartValue);
        help("先直接连接耳机，直连可用就不请求 Root。请先在设置中选择耳机，并允许读取媒体播放状态；实际声音与双向切换仍需在设备上确认。");

        section("连接与音频诊断");
        LinearLayout diag=card();diag.setPadding(0,dp(4),0,dp(4));
        diagAacp=keyValue(diag,"耳机控制 (AACP)");
        diagHandoff=keyValue(diag,"自动交接");
        diagA2dp=keyValue(diag,"媒体音频 (A2DP)");
        diagHeadset=keyValue(diag,"通话音频 (HFP/HSP)");
        diagRoute=keyValue(diag,"系统路由");
        diagCapability=keyValue(diag,"连接能力");
        diagBridge=keyValue(diag,"兼容桥接");
        diagMedia=keyValue(diag,"媒体状态权限");

        section("兼容桥接（进阶）");
        LinearLayout bridge=card();
        actionRow(bridge,"检查 Root 与运行环境","只读检查，不会准备桥接",t.accent,actions::checkEnvironment);
        actionRow(bridge,"准备兼容桥接","仅在明确缺少系统兼容能力时需要",t.accent,actions::prepareBridge);
        divider(bridge);
        detach(environmentStatus);
        environmentStatus.setTextColor(t.muted);
        environmentStatus.setPadding(dp(14),dp(12),dp(14),dp(12));
        environmentStatus.setBackground(t.rounded(t.surfaceSubtle,UiTheme.INNER_RADIUS_DP));
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,-2);ep.setMargins(dp(16),dp(12),dp(16),dp(16));
        bridge.addView(environmentStatus,ep);
        help("桥接只使用精确匹配的兼容适配；临时 Root 需要单独确认，且会在应用数据目录写入运行文件。");
    }

    // ---------------------------------------------------------------- SETTINGS

    private void settingsPage(){
        section("目标耳机");
        LinearLayout device=card();
        FrameLayout pickerBox=new FrameLayout(activity);
        detach(picker);
        picker.setContentDescription("选择已配对的耳机");
        picker.setBackground(t.rounded(t.surfaceSubtle,UiTheme.INNER_RADIUS_DP));
        picker.setPadding(dp(8),0,dp(40),0);
        pickerBox.addView(picker,new FrameLayout.LayoutParams(-1,dp(52)));
        TextView chevron=text("⌄",18,t.muted);chevron.setGravity(Gravity.CENTER);chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        pickerBox.addView(chevron,new FrameLayout.LayoutParams(dp(40),dp(52),Gravity.END|Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.setMargins(dp(16),dp(16),dp(16),dp(12));
        device.addView(pickerBox,pp);
        actionRow(device,"刷新设备与状态",null,t.accent,actions::refreshDevices);
        actionRow(device,"系统蓝牙设置","配对、重命名或移除耳机",t.accent,actions::openBluetooth);

        section("权限");
        LinearLayout perms=card();
        permBluetooth=permissionRow(perms,"附近设备","读取已配对耳机并建立连接",actions::requestNearby);
        permMedia=permissionRow(perms,"媒体播放状态","自动交接需要；耳机控制不依赖此项",actions::openMediaAccess);
        permNotifications=permissionRow(perms,"服务通知","在通知栏显示连接状态与快捷操作",actions::requestNotifications);

        section("运行记录");
        LinearLayout logs=card();
        detach(logOutput);
        logOutput.setTextColor(t.muted);
        logOutput.setPadding(dp(14),dp(12),dp(14),dp(12));
        logOutput.setBackground(t.rounded(t.surfaceSubtle,UiTheme.INNER_RADIUS_DP));
        logOutput.setMinHeight(dp(72));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(12),dp(12),dp(12),dp(12));
        logs.addView(logOutput,lp);

        TextView version=strong(VERSION_LABEL,12,t.muted);version.setGravity(Gravity.CENTER);version.setPadding(0,dp(10),0,dp(2));body.addView(version,new LinearLayout.LayoutParams(-1,-2));
        TextView note=text("耳机控制与交接的设备验收仍在进行。",11,t.muted);note.setGravity(Gravity.CENTER);body.addView(note,new LinearLayout.LayoutParams(-1,-2));
    }

    private TextView permissionRow(LinearLayout c,String name,String subtitle,Runnable task){
        LinearLayout r=listRow(c);titled(r,name,subtitle,t.ink);
        TextView b=badge("未检查");r.addView(b);
        tap(r,task);
        return b;
    }

    // ---------------------------------------------------------------- binding

    void bindSummary(StatusSummary summary){
        currentSummary=summary;
        if(handoffShortcut!=null)handoffShortcut.setText(handoffBrief());
        if(summary==null||summaryBadge==null||summaryHeadline==null)return;
        int level=summary.level==StatusSummary.Level.NORMAL?0:summary.level==StatusSummary.Level.ATTENTION?1:2;
        tone(summaryBadge,level,summary.levelLabel());
        if(summaryDot!=null)summaryDot.setBackground(oval(level==0?t.ok:level==1?t.warn:t.danger));
        summaryHeadline.setText(summary.headline);
        if(summaryDetail!=null){summaryDetail.setText(summary.detail);summaryDetail.setVisibility(summary.detail.isEmpty()?View.GONE:View.VISIBLE);}
        if(summaryAction!=null){
            if(summary.hasPrimaryAction()){summaryAction.setText(summary.primaryActionLabel());summaryAction.setContentDescription(summary.primaryActionLabel());summaryAction.setVisibility(View.VISIBLE);}
            else summaryAction.setVisibility(View.GONE);
        }
    }

    /** Optional runtime flags supplied by the activity; unknown values are shown as "未检查". */
    void bindRuntimeFlags(boolean btGranted,boolean mediaAccessGranted,boolean notifGranted,boolean autoStartOptIn,boolean autoStartSuppressed){
        btFlag=btGranted;mediaFlag=mediaAccessGranted;notifFlag=notifGranted;autoStartFlag=autoStartOptIn;autoStartSuppressedFlag=autoStartSuppressed;
        bindFlagsToViews();
    }

    private void bindFlagsToViews(){
        Boolean bt=btFlag!=null?btFlag:audioSnapshot!=null?Boolean.valueOf(audioSnapshot.permissionGranted):null;
        Boolean media=mediaFlag!=null?mediaFlag:capabilities!=null?Boolean.valueOf(mediaGranted):null;
        permission(permBluetooth,bt);permission(permMedia,media);permission(permNotifications,notifFlag);
        if(autoStartValue!=null){
            if(autoStartFlag==null)tone(autoStartValue,4,"未检查");
            else if(!autoStartFlag)tone(autoStartValue,4,"未开启");
            else if(Boolean.TRUE.equals(autoStartSuppressedFlag))tone(autoStartValue,1,"已暂停");
            else tone(autoStartValue,3,"已开启");
        }
        if(diagMedia!=null)diagMedia.setText(media==null?"未检查":media?"已允许":"未授权：耳机控制仍可用，自动交接不可用");
    }
    private void permission(TextView b,Boolean granted){
        if(b==null)return;
        if(granted==null)tone(b,4,"未检查");else tone(b,granted?0:1,granted?"已允许":"未授权");
    }

    void bindAudio(SamsungAudioSnapshot state){
        audioSnapshot=state;
        if(diagA2dp!=null)diagA2dp.setText(state!=null?SamsungAudioPolicy.formatA2dpSummary(state):actions.connectionStatus());
        if(diagHeadset!=null)diagHeadset.setText(SamsungAudioPolicy.formatHeadsetSummary(state));
        if(diagRoute!=null){
            String route=SamsungAudioPolicy.formatRouteSummary(state);
            if(state!=null&&state.permissionGranted&&state.targetAddress.isEmpty())route="请先在设置中选择耳机";
            else if(state!=null&&!state.routeDetail.isEmpty())route=route+"\n"+state.routeDetail;
            diagRoute.setText(route);
        }
        if(btFlag==null)bindFlagsToViews();
    }

    void bindCapabilities(ConnectionPolicy.Snapshot state,boolean mediaAllowed){
        capabilities=state;mediaGranted=mediaAllowed;
        if(diagCapability!=null)diagCapability.setText(state==null||state.description==null?"控制会话尚未启动":state.description);
        if(diagBridge!=null)diagBridge.setText(state==null?"尚未评估":state.compatibilityRequired?"需要兼容桥接":"当前无需兼容桥接");
        if(mediaFlag==null)bindFlagsToViews();
    }

    void bind(EarbudSnapshot state,String address){
        bind(state,address,sessionStatus);
    }

    void bind(EarbudSnapshot state,String address,SessionStatus status){
        String nextTarget=address==null?"":address;
        if(!target.equals(nextTarget)){target=nextTarget;lastLeft=null;lastRight=null;lastCase=null;if(noise!=null)noise.resetCapabilities();}
        if(state==null)state=EarbudSnapshot.empty(target,null);
        snapshot=state;sessionStatus=status;
        String name=actions.deviceName();
        if(sidebarDevice!=null)sidebarDevice.setText(name);
        if(heroName!=null)heroName.setText(name);
        if(heroModel!=null){heroModel.setText(state.modelIdentityLabel());heroModel.setContentDescription("耳机型号："+state.modelIdentityLabel());}
        if(artwork!=null&&modelPlate!=null){
            boolean pro3=state.isPro3();
            artwork.setVisibility(pro3?View.VISIBLE:View.GONE);
            modelPlate.setVisibility(pro3?View.GONE:View.VISIBLE);
            plateTitle.setText(state.modelKnown()?state.model.name:"型号尚未识别");
            plateCaption.setText(state.modelKnown()?"此型号暂无产品图":state.modelNumber.isEmpty()?"等待耳机回报型号信息":"型号编号 "+state.modelNumber+" 未收录");
        }
        if(diagAacp!=null){
            String ctrl=status!=null&&status.serviceRunning?status.controlDescription:(state.status==null?(state.ready?"通道就绪":"未连接"):state.status);
            if(state.status!=null&&state.status.contains("未确认")&&!ctrl.contains(state.status))ctrl=ctrl+"（"+state.status+"）";
            diagAacp.setText(ctrl);
        }
        if(diagHandoff!=null){
            String h=status!=null?status.handoffDescription:"关闭";
            if(status!=null&&!status.handoffNotice.isEmpty())h=h+"\n"+status.handoffNotice;
            diagHandoff.setText(h);
        }
        if(handoffShortcut!=null)handoffShortcut.setText(handoffBrief());

        if(state.ready||state.leftBattery!=null)lastLeft=state.leftBattery;
        if(state.ready||state.rightBattery!=null)lastRight=state.rightBattery;
        if(state.ready||state.caseBattery!=null)lastCase=state.caseBattery;
        if(leftPill!=null){
            leftPill.bind(lastLeft,state.ready);rightPill.bind(lastRight,state.ready);casePill.bind(lastCase,state.ready);
            boolean any=lastLeft!=null||lastRight!=null||lastCase!=null;
            batteryHint.setVisibility(state.ready?View.GONE:View.VISIBLE);
            batteryHint.setText(any?"电量未更新 · 显示最近一次读数":"连接耳机后显示电量");
        }

        boolean unconfirmed=state.status!=null&&state.status.contains("未确认");
        if(noise!=null){
            boolean supported=state.supportsSetting(EarbudCommands.LISTENING_MODE);
            Integer mode=state.value(EarbudCommands.LISTENING_MODE);
            boolean pending=state.pending.contains(EarbudCommands.LISTENING_MODE);
            noise.setVisibility(supported?View.VISIBLE:View.GONE);
            noise.bind(mode,EarbudUiPolicy.canWrite(state.ready,mode,pending),pending,
                state.supportsListeningMode(EarbudCommands.MODE_ADAPTIVE),state.supportsListeningMode(EarbudCommands.MODE_OFF));
            if(!supported)tone(modeChip,4,"此型号不支持");
            else if(pending)tone(modeChip,1,"等待耳机确认");
            else if(mode==null)tone(modeChip,4,"尚未读取");
            else tone(modeChip,state.ready?3:4,modeLabel(mode));
            String hint=!supported?"此型号不支持噪声控制。"
                :pending?"已发送，等待耳机确认；当前仍显示已读取的模式。"
                :unconfirmed?state.status+"；当前仍显示最近回报值。"
                :mode==null?"连接耳机并读取当前模式后可切换。"
                :!state.ready?"控制通道未就绪，显示最近一次读取的模式。"
                :"自适应与关闭仅在耳机曾回报该模式后显示。";
            modeHint.setText(hint);modeHint.setTextColor(unconfirmed?t.warn:t.muted);modeHint.setVisibility(View.VISIBLE);
        }
        for(SettingRow r:settings){
            Boolean value=state.enabled(r.id);boolean pending=state.pending.contains(r.id);boolean supported=state.supportsSetting(r.id);
            r.toggle.bind(value,supported&&value!=null&&EarbudUiPolicy.canWrite(state.ready,state.value(r.id),pending));
            r.detail.setText(!supported?"此型号不支持":pending?"等待耳机确认":value==null?"尚未读取":!state.ready?"控制通道未就绪":value?"已开启":"已关闭");
            r.detail.setTextColor(pending?t.warn:t.muted);
        }
        if(feedback!=null){
            if(unconfirmed){feedback.setText(state.status+"；当前仍显示最近回报值");feedback.setTextColor(t.warn);}
            else if(!state.pending.isEmpty()){feedback.setText("已提交，等待耳机确认");feedback.setTextColor(t.muted);}
            else if(feedback.getText()!=null&&feedback.getText().toString().contains("未确认")){feedback.setText("");feedback.setTextColor(t.muted);}
        }
        updateLocalVolume();
    }

    private String handoffBrief(){
        if(sessionStatus==null||!sessionStatus.serviceRunning)return "未运行";
        String h=sessionStatus.handoffDescription;
        return h.isEmpty()?"已连接":h;
    }

    private static String modeLabel(int mode){
        if(mode==EarbudCommands.MODE_ANC)return "降噪";
        if(mode==EarbudCommands.MODE_TRANSPARENCY)return "通透";
        if(mode==EarbudCommands.MODE_ADAPTIVE)return "自适应";
        if(mode==EarbudCommands.MODE_OFF)return "关闭";
        return "未知模式";
    }

    private void submit(int id,int value){
        boolean accepted=actions.setSetting(id,value);
        message(accepted?"已提交，等待耳机确认":"当前无法更改，请检查耳机控制通道");
    }
    private void message(String value){
        if(feedback!=null){feedback.setText(value);feedback.setTextColor(t.muted);}
        Toast.makeText(activity,value,Toast.LENGTH_SHORT).show();
    }

    void updateLocalVolume(){
        if(volume==null||audio==null)return;
        try{
            int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),current=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            volume.bind(current,max,!audio.isVolumeFixed());
            volumeValue.setText(Math.round(100f*current/Math.max(1,max))+"%");
        }catch(RuntimeException e){volume.setEnabled(false);volumeValue.setText("不可用");}
    }

    /** Optional themed adapter for the device picker so labels stay legible in both themes. */
    ArrayAdapter<String> deviceAdapter(List<String> labels){
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_item,labels){
            @Override public View getView(int position,View convertView,ViewGroup parent){return style(super.getView(position,convertView,parent),false);}
            @Override public View getDropDownView(int position,View convertView,ViewGroup parent){return style(super.getDropDownView(position,convertView,parent),true);}
            private View style(View v,boolean dropDown){
                if(v instanceof TextView){TextView tv=(TextView)v;tv.setTextColor(t.ink);tv.setTextSize(15);if(dropDown){tv.setBackgroundColor(t.surface);tv.setPadding(dp(16),dp(12),dp(16),dp(12));}}
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    // ---------------------------------------------------------------- small components

    private final class BatteryPill {
        final LinearLayout box;
        final TextView value;
        final LevelBar bar;
        final String label;
        BatteryPill(String label){
            this.label=label;
            box=column();box.setPadding(dp(14),dp(12),dp(14),dp(14));
            box.setBackground(t.rounded(t.surfaceSubtle,20));
            box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            TextView name=text(label,12,t.muted);name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);box.addView(name);
            value=heavy("--",20,t.muted);value.setPadding(0,dp(2),0,dp(8));value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);box.addView(value);
            bar=new LevelBar(activity,t.dark?0x1FFFFFFF:0x14000000);
            box.addView(bar,new LinearLayout.LayoutParams(-1,dp(5)));
            bind(null,false);
        }
        void bind(Integer level,boolean fresh){
            boolean low=level!=null&&level<=20;
            value.setText(level==null?"--":level+"%");
            value.setTextColor(level==null?t.muted:!fresh?t.muted:low?t.danger:t.ink);
            bar.bind(level,!fresh?t.muted:low?t.danger:t.ok);
            box.setContentDescription(label+"电量"+(level==null?"未知":level+"%")+(fresh?"":"，当前未更新"));
        }
    }

    private static final class LevelBar extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect=new RectF();
        private final int track;
        private int fill;
        private float level=-1f;
        LevelBar(Context context,int track){super(context);this.track=track;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void bind(Integer value,int color){level=value==null?-1f:Math.max(0,Math.min(100,value))/100f;fill=color;invalidate();}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight(),r=h/2f;
            paint.setColor(track);rect.set(0,0,w,h);c.drawRoundRect(rect,r,r,paint);
            if(level>0f){paint.setColor(fill);rect.set(0,0,Math.max(h,w*level),h);c.drawRoundRect(rect,r,r,paint);}
        }
    }

    private static final class SettingRow {
        final int id;final PodControls.GlassSwitch toggle;final TextView detail;
        SettingRow(int id,PodControls.GlassSwitch toggle,TextView detail){this.id=id;this.toggle=toggle;this.detail=detail;}
    }
}
