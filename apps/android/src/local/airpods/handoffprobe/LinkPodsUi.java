package local.airpods.handoffprobe;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.media.AudioManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

/** Adaptive native settings page; the activity remains the owner of service/Root actions. */
final class LinkPodsUi {
    interface Actions {
        void openBluetooth(); void requestNearby(); void openMediaAccess(); void requestNotifications();
        void refreshDevices(); void enableHandoff(); void stopHandoff(); void checkEnvironment();
        boolean setSetting(int id,int value);
        String deviceName(); String connectionStatus();
    }
    static final String HOME="设备", AUDIO="音频与聆听", PRESS="按压控制", CONNECTION="连接", SETTINGS="LinkPods 设置";
    private final Activity activity;
    private final Actions actions;
    private final Spinner picker;
    final TextView environmentStatus,logOutput;
    private final AudioManager audio;
    private final boolean dark;
    private final int bg,side,group,ink,muted,line,blue;
    private LinearLayout root,body,sidebar;
    private TextView title,connection,session,batteries,modeStatus,volumeValue,feedback,sidebarDevice,deviceNameValue;
    private PodControls.NoiseSelector noise;
    private PodControls.VolumeSlider volume;
    private final ArrayList<SettingRow> settings=new ArrayList<>();
    private EarbudSnapshot snapshot;
    private Integer lastLeft,lastRight,lastCase;
    private String target="",page=HOME;
    private boolean tablet;
    private final ArrayList<String> history=new ArrayList<>();

    LinkPodsUi(Activity a,Spinner spinner,Actions actions,String restoredPage) {
        activity=a;picker=spinner;this.actions=actions;audio=a.getSystemService(AudioManager.class);
        dark=(a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        bg=dark?Color.BLACK:0xfff2f2f7;side=dark?0xff171719:0xfff8f8fb;
        group=dark?0xff1c1c1e:Color.WHITE;ink=dark?0xfff5f5f7:0xff171719;muted=dark?0xffa1a1aa:0xff777780;
        line=dark?0xff343438:0xffe5e5ea;blue=dark?0xff0a84ff:0xff007aff;
        environmentStatus=text("Root 与控制通道尚未检测",14,muted);
        logOutput=text("",12,muted);logOutput.setTextIsSelectable(true);
        if(restoredPage!=null&&!restoredPage.isEmpty())page=restoredPage;
        build();
    }
    int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    String page(){return page;}
    private TextView text(String value,int sp,int color){TextView t=new TextView(activity);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setFontFeatureSettings("kern");return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void clickable(View v,Runnable action){v.setClickable(true);v.setFocusable(true);v.setOnClickListener(w->action.run());v.setBackground(new RippleDrawable(ColorStateList.valueOf(dark?0x30ffffff:0x16000000),shape(Color.TRANSPARENT,16),shape(Color.WHITE,16)));}
    private void detach(View v){if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);}
    private void build() {
        // The 720dp compact split accommodates the target tablet's 738dp portrait window.
        int width=activity.getResources().getConfiguration().screenWidthDp;
        tablet=width>=720;
        root=new LinearLayout(activity);root.setOrientation(LinearLayout.HORIZONTAL);root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
        activity.getWindow().setStatusBarColor(bg);activity.getWindow().setNavigationBarColor(bg);
        android.view.WindowInsetsController bars=activity.getWindow().getDecorView().getWindowInsetsController();
        if(bars!=null)bars.setSystemBarsAppearance(dark?0:android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        if(tablet)buildSidebar(width>=840?240:200);
        LinearLayout main=column();root.addView(main,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout bar=new LinearLayout(activity);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(12),0,dp(12),0);main.addView(bar,new LinearLayout.LayoutParams(-1,dp(60)));
        TextView back=text(!HOME.equals(page)?"‹ 返回":tablet?"":"设置",16,blue);back.setMinWidth(dp(56));back.setMinHeight(dp(48));clickable(back,()->{if(!HOME.equals(page))back();else show(SETTINGS);});bar.addView(back,new LinearLayout.LayoutParams(dp(68),-1));
        title=text(HOME.equals(page)?actions.deviceName():page,17,ink);title.setGravity(Gravity.CENTER);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);bar.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        TextView brand=text("LinkPods",12,muted);brand.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);bar.addView(brand,new LinearLayout.LayoutParams(dp(68),-1));
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setClipToPadding(false);main.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout frame=new FrameLayout(activity);scroll.addView(frame,new ScrollView.LayoutParams(-1,-2));
        body=new LinearLayout(activity){@Override protected void onMeasure(int ws,int hs){int available=View.MeasureSpec.getSize(ws);super.onMeasure(View.MeasureSpec.makeMeasureSpec(Math.min(dp(620),available),View.MeasureSpec.EXACTLY),hs);}};
        body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(width<400?16:24),dp(4),dp(width<400?16:24),dp(28));
        FrameLayout.LayoutParams content=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);frame.addView(body,content);
        settings.clear();deviceNameValue=null;noise=null;volume=null;volumeValue=null;batteries=null;connection=null;session=null;modeStatus=null;feedback=null;
        if(HOME.equals(page))home();
        else if(AUDIO.equals(page))audioPage();
        else if(PRESS.equals(page))pressPage();
        else if(CONNECTION.equals(page))connectionPage();
        else if(SETTINGS.equals(page))appSettings();
        else advanced(page);
        activity.setContentView(root);root.requestApplyInsets();
        if(snapshot!=null)bind(snapshot,target);else updateLocalVolume();
    }
    private void buildSidebar(int width){
        sidebar=column();sidebar.setPadding(dp(12),dp(20),dp(12),dp(16));sidebar.setBackgroundColor(side);
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.addView(sidebar);root.addView(scroll,new LinearLayout.LayoutParams(dp(width),-1));
        TextView heading=text("设置",28,ink);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(dp(12),0,0,dp(22));sidebar.addView(heading);
        TextView app=text("LinkPods",13,muted);app.setPadding(dp(12),0,0,dp(12));sidebar.addView(app);
        nav("◉  "+actions.deviceName(),HOME);
        TextView caption=text("耳机",12,muted);caption.setPadding(dp(12),dp(25),0,dp(8));sidebar.addView(caption);
        nav("♫  音频与聆听",AUDIO);nav("◎  按压控制",PRESS);nav("⇄  连接",CONNECTION);
        TextView appCaption=text("应用",12,muted);appCaption.setPadding(dp(12),dp(25),0,dp(8));sidebar.addView(appCaption);nav("⚙  LinkPods 设置",SETTINGS);
        View divider=new View(activity);divider.setBackgroundColor(line);root.addView(divider,new LinearLayout.LayoutParams(dp(1),-1));
    }
    private void nav(String label,String destination){TextView row=text(label,15,ink);row.setPadding(dp(12),dp(10),dp(8),dp(10));row.setMinHeight(dp(48));row.setMaxLines(2);clickable(row,()->show(destination));
        if(HOME.equals(destination))sidebarDevice=row;
        boolean selected=page.equals(destination)||(destination.equals(PRESS)&&(page.contains("长按")||page.equals("按压速度")))||(destination.equals(AUDIO)&&(page.equals("麦克风")||page.equals("辅助功能")));
        row.setSelected(selected);if(selected){row.setTextColor(Color.WHITE);row.setBackground(shape(blue,13));}
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(4);sidebar.addView(row,p);}
    private void show(String destination){if(!page.equals(destination)){history.add(page);page=destination;build();}}
    boolean back(){if(HOME.equals(page))return false;page=history.isEmpty()?HOME:history.remove(history.size()-1);build();return true;}
    private void caption(String value){TextView t=text(value,12,muted);t.setPadding(dp(16),dp(12),dp(12),dp(10));body.addView(t);}
    private void help(String value){TextView t=text(value,12,muted);t.setPadding(dp(16),dp(4),dp(12),dp(16));t.setLineSpacing(dp(3),1);body.addView(t);}
    private LinearLayout group(){LinearLayout l=column();l.setBackground(shape(group,25));l.setClipToOutline(true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(16);body.addView(l,p);return l;}
    private void divider(LinearLayout g){if(g.getChildCount()==0)return;View v=new View(activity);v.setBackgroundColor(line);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(.5f));p.leftMargin=dp(20);g.addView(v,p);}
    private LinearLayout row(LinearLayout g){divider(g);LinearLayout r=new LinearLayout(activity);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(20),dp(7),dp(12),dp(7));r.setMinimumHeight(dp(56));g.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;}
    private TextView navigation(LinearLayout g,String name,String value,String next){LinearLayout r=row(g);TextView label=text(name,16,ink);r.addView(label,new LinearLayout.LayoutParams(0,-2,1));TextView detail=text(value+"  ›",14,muted);detail.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);detail.setMaxWidth(dp(160));detail.setMaxLines(2);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);r.addView(detail);clickable(r,()->show(next));return detail;}
    private void info(LinearLayout g,String name,String value){LinearLayout r=row(g);r.addView(text(name,16,ink),new LinearLayout.LayoutParams(0,-2,1));TextView v=text(value,14,muted);v.setMaxWidth(dp(180));v.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);r.addView(v);}
    private void action(LinearLayout g,String name,Runnable task){LinearLayout r=row(g);TextView t=text(name,16,blue);r.addView(t,new LinearLayout.LayoutParams(-1,-2));clickable(r,task);}
    private void pageTitle(String name){TextView t=text(name,28,ink);t.setTypeface(null,Typeface.BOLD);t.setPadding(dp(4),dp(14),0,dp(22));body.addView(t);t.setAccessibilityHeading(true);}
    private void hero(){
        LinearLayout illustration=new LinearLayout(activity);illustration.setGravity(Gravity.CENTER);illustration.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for(String name:new String[]{"lp_earbuds","lp_case"}){ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);int id=activity.getResources().getIdentifier(name,"drawable",activity.getPackageName());if(id!=0)image.setImageResource(id);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(174),1);p.setMargins(dp(6),0,dp(6),0);illustration.addView(image,p);}
        LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(-1,dp(190));imageParams.topMargin=dp(12);body.addView(illustration,imageParams);
        batteries=text("左耳 --   ·   右耳 --   ·   充电盒 --",13,muted);batteries.setGravity(Gravity.CENTER);batteries.setPadding(0,dp(6),0,dp(8));body.addView(batteries);
        connection=text(actions.connectionStatus(),12,muted);connection.setGravity(Gravity.CENTER);connection.setPadding(dp(8),dp(2),dp(8),dp(3));body.addView(connection);
        session=text("耳机控制：尚未读取",12,muted);session.setGravity(Gravity.CENTER);session.setPadding(dp(8),0,dp(8),dp(18));body.addView(session);
    }
    private void home(){hero();LinearLayout name=group();deviceNameValue=navigation(name,"名称",actions.deviceName(),"名称");noiseGroup();
        caption("按住 AirPods");LinearLayout press=group();navigation(press,"左耳","暂未接入","左耳长按");navigation(press,"右耳","暂未接入","右耳长按");
        commonSwitches();volumeGroup();LinearLayout extras=group();navigation(extras,"自动设备切换","前往设置",CONNECTION);navigation(extras,"麦克风","暂未接入","麦克风");navigation(extras,"辅助功能","","辅助功能");
        LinearLayout app=group();navigation(app,"LinkPods 设置","",SETTINGS);feedback=text("",12,muted);feedback.setPadding(dp(16),0,dp(16),dp(12));body.addView(feedback);
    }
    private void noiseGroup(){caption("噪声控制");noise=new PodControls.NoiseSelector(activity,dark,value->submit(EarbudCommands.LISTENING_MODE,value));body.addView(noise,new LinearLayout.LayoutParams(-1,dp(94)));
        modeStatus=text("尚未读取耳机模式",12,muted);modeStatus.setPadding(dp(16),dp(4),dp(12),dp(12));body.addView(modeStatus);}
    private void commonSwitches(){LinearLayout g=group();setting(g,"个性化音量",EarbudCommands.PERSONALIZED_VOLUME);setting(g,"对话感知",EarbudCommands.CONVERSATION_AWARENESS);setting(g,"自动入耳检测",EarbudCommands.EAR_DETECTION);
        help("耳机回报当前设置后才可更改。未读取的功能显示为不可用。");}
    private void setting(LinearLayout g,String label,int id){LinearLayout r=row(g);LinearLayout labels=column();TextView name=text(label,16,ink);TextView detail=text("尚未读取",11,muted);labels.addView(name);labels.addView(detail);r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        PodControls.GlassSwitch toggle=new PodControls.GlassSwitch(activity,dark,label,v->submit(id,EarbudCommands.booleanValue(v==1)));toggle.bind(null,false);r.addView(toggle,new LinearLayout.LayoutParams(dp(76),dp(48)));settings.add(new SettingRow(id,toggle,detail));}
    private void volumeGroup(){LinearLayout g=group();g.setPadding(dp(20),dp(12),dp(20),dp(9));LinearLayout label=new LinearLayout(activity);label.setGravity(Gravity.CENTER_VERTICAL);label.addView(text("媒体音量",16,ink),new LinearLayout.LayoutParams(0,dp(32),1));volumeValue=text("",14,muted);label.addView(volumeValue);g.addView(label);
        LinearLayout sliderRow=new LinearLayout(activity);sliderRow.setGravity(Gravity.CENTER_VERTICAL);TextView quiet=text("−",20,muted);quiet.setGravity(Gravity.CENTER);sliderRow.addView(quiet,new LinearLayout.LayoutParams(dp(22),dp(48)));
        volume=new PodControls.VolumeSlider(activity,dark,value->{if(audio!=null){try{audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);updateLocalVolume();}catch(SecurityException e){message("系统未允许调节媒体音量");}}});sliderRow.addView(volume,new LinearLayout.LayoutParams(0,dp(48),1));TextView loud=text("+",20,muted);loud.setGravity(Gravity.CENTER);sliderRow.addView(loud,new LinearLayout.LayoutParams(dp(22),dp(48)));g.addView(sliderRow);
        TextView local=text("此 Android 设备的音量",11,muted);local.setPadding(0,0,0,dp(3));g.addView(local);}
    private void audioPage(){pageTitle(AUDIO);noiseGroup();volumeGroup();commonSwitches();LinearLayout g=group();setting(g,"单耳降噪",EarbudCommands.ONE_BUD_ANC);setting(g,"音量滑动",EarbudCommands.VOLUME_SWIPE);navigation(g,"麦克风","暂未接入","麦克风");}
    private void pressPage(){pageTitle(PRESS);LinearLayout g=group();navigation(g,"左耳长按","暂未接入","左耳长按");navigation(g,"右耳长按","暂未接入","右耳长按");navigation(g,"按压速度","暂未接入","按压速度");help("按压设置当前暂不可用。");}
    private void connectionPage(){pageTitle("自动设备切换");help("启用后，LinkPods 根据此设备的播放状态协调耳机交接。请先在应用设置中选择耳机，并允许读取媒体播放状态。");
        LinearLayout g=group();action(g,"启用自动交接",actions::enableHandoff);action(g,"停止自动交接",actions::stopHandoff);navigation(g,"设备与权限","",SETTINGS);attachStatus();help("启用前会检查运行环境；临时 Root 另有确认。音频连接和控制通道就绪分别显示，实际声音与双向切换仍需确认。");}
    private void appSettings(){pageTitle(SETTINGS);caption("设备");LinearLayout device=group();detach(picker);picker.setContentDescription("选择已配对的耳机");device.addView(picker,new LinearLayout.LayoutParams(-1,dp(56)));action(device,"允许附近设备权限",actions::requestNearby);action(device,"配对或管理耳机",actions::openBluetooth);action(device,"刷新设备与连接状态",actions::refreshDevices);
        caption("权限");LinearLayout permissions=group();action(permissions,"允许读取媒体播放状态",actions::openMediaAccess);action(permissions,"允许服务通知",actions::requestNotifications);
        caption("运行环境");LinearLayout runtime=group();action(runtime,"检查 Root 与运行环境",actions::checkEnvironment);action(runtime,"启用自动交接",actions::enableHandoff);action(runtime,"停止自动交接",actions::stopHandoff);attachStatus();
        caption("运行记录");LinearLayout logs=group();detach(logOutput);logOutput.setPadding(dp(16),dp(12),dp(16),dp(12));logs.addView(logOutput,new LinearLayout.LayoutParams(-1,-2));help("LinkPods 1.0 · 耳机控制与交接的设备验收仍在进行。");}
    private void attachStatus(){LinearLayout g=group();detach(environmentStatus);environmentStatus.setPadding(dp(20),dp(14),dp(20),dp(14));g.addView(environmentStatus,new LinearLayout.LayoutParams(-1,-2));}
    private void advanced(String name){pageTitle(name);LinearLayout g=group();if(name.equals("名称")){info(g,"蓝牙设备名称",actions.deviceName());action(g,"在系统蓝牙设置中管理",actions::openBluetooth);help("名称由系统蓝牙设备信息读取。");}
        else if(name.equals("辅助功能")){setting(g,"单耳降噪",EarbudCommands.ONE_BUD_ANC);setting(g,"音量滑动",EarbudCommands.VOLUME_SWIPE);navigation(g,"按压速度","暂未接入","按压速度");help("仅可更改已由耳机回报且当前可用的选项。");}
        else{info(g,"当前设置","暂未接入");help("此选项尚未接入耳机设置协议，当前无法修改。");}}
    private void submit(int id,int value){boolean accepted=actions.setSetting(id,value);message(accepted?"已提交，等待耳机确认":"当前无法更改，请检查耳机控制通道");}
    private void message(String value){if(feedback!=null)feedback.setText(value);Toast.makeText(activity,value,Toast.LENGTH_SHORT).show();}
    void bind(EarbudSnapshot state,String address){
        if(!target.equals(address==null?"":address)){target=address==null?"":address;lastLeft=null;lastRight=null;lastCase=null;if(noise!=null)noise.resetCapabilities();}
        snapshot=state;
        if(title!=null&&HOME.equals(page))title.setText(actions.deviceName());
        if(deviceNameValue!=null)deviceNameValue.setText(actions.deviceName()+"  ›");
        if(sidebarDevice!=null)sidebarDevice.setText("◉  "+actions.deviceName());
        if(connection!=null)connection.setText(actions.connectionStatus());
        if(session!=null)session.setText("耳机控制："+(state.status==null?(state.ready?"通道就绪":"尚未读取"):state.status));
        if(state.ready||state.leftBattery!=null)lastLeft=state.leftBattery;
        if(state.ready||state.rightBattery!=null)lastRight=state.rightBattery;
        if(state.ready||state.caseBattery!=null)lastCase=state.caseBattery;
        if(batteries!=null){batteries.setText("左耳 "+battery(lastLeft)+"   ·   右耳 "+battery(lastRight)+"   ·   充电盒 "+battery(lastCase)+(state.ready?"":"  · 未更新"));batteries.setContentDescription("左耳电量"+batterySpoken(lastLeft)+"，右耳电量"+batterySpoken(lastRight)+"，充电盒电量"+batterySpoken(lastCase)+(state.ready?"":"，当前未更新"));}
        if(noise!=null){Integer mode=state.value(EarbudCommands.LISTENING_MODE);boolean pending=state.pending.contains(EarbudCommands.LISTENING_MODE);noise.bind(mode,EarbudUiPolicy.canWrite(state.ready,mode,pending),pending,state.supportsListeningMode(EarbudCommands.MODE_ADAPTIVE),state.supportsListeningMode(EarbudCommands.MODE_OFF));
            modeStatus.setText(pending?"已发送，等待耳机确认；当前仍显示已读取模式":mode==null?"尚未读取耳机模式":state.status!=null&&state.status.contains("未确认")?state.status:"自适应与关闭仅在耳机曾回报该模式后显示");}
        for(SettingRow r:settings){Boolean value=state.enabled(r.id);boolean pending=state.pending.contains(r.id);r.toggle.bind(value,value!=null&&EarbudUiPolicy.canWrite(state.ready,state.value(r.id),pending));r.detail.setText(pending?"等待耳机确认":value==null?"尚未读取":!state.ready?"控制通道未就绪":value?"已开启":"已关闭");}
        updateLocalVolume();
    }
    private String battery(Integer value){return value==null?"--":value+"%";}
    private String batterySpoken(Integer value){return value==null?"未知":value+"%";}
    void updateLocalVolume(){if(volume==null||audio==null)return;try{int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),current=audio.getStreamVolume(AudioManager.STREAM_MUSIC);volume.bind(current,max,!audio.isVolumeFixed());volumeValue.setText(Math.round(100f*current/Math.max(1,max))+"%");}catch(RuntimeException e){volume.setEnabled(false);volumeValue.setText("不可用");}}
    private static final class SettingRow {final int id;final PodControls.GlassSwitch toggle;final TextView detail;SettingRow(int id,PodControls.GlassSwitch toggle,TextView detail){this.id=id;this.toggle=toggle;this.detail=detail;}}
}
