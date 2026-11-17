package local.airpods.handoffprobe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.OvershootInterpolator;

/** Native, dependency-free controls. State binding never calls a change listener. */
final class PodControls {
    interface Change { void changed(int value); }

    abstract static class Control extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final int ink, track, thumb, accent;
        final float density;
        float position, pressScale = 1f, downX, downY;
        boolean dragging;
        ValueAnimator animator;
        Change listener;
        Control(Context context, boolean dark) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            ink = dark ? 0xfff5f5f7 : 0xff171719;
            track = dark ? 0xff303034 : 0xffe5e5ea;
            thumb = dark ? 0xff77777c : Color.WHITE;
            accent = dark ? 0xff30d158 : 0xff34c759;
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        float dp(float n) { return n * density; }
        void pill(Canvas c, float l, float t, float r, float b, int color) {
            paint.setColor(color); paint.setStyle(Paint.Style.FILL);
            rect.set(l,t,r,b); c.drawRoundRect(rect,(b-t)/2,(b-t)/2,paint);
        }
        void glass(Canvas c, float l, float t, float r, float b) {
            float w=(r-l)*pressScale, h=(b-t)*pressScale, x=(l+r)/2, y=(t+b)/2;
            paint.setShadowLayer(dp(dragging?4:2),0,dp(1),0x22000000);
            pill(c,x-w/2,y-h/2,x+w/2,y+h/2,dragging ? (thumb & 0x00ffffff)|0xd9000000 : thumb);
            paint.clearShadowLayer();
            paint.setColor(0x70ffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.65f));
            rect.set(x-w/2+dp(.5f),y-h/2+dp(.5f),x+w/2-dp(.5f),y+h/2-dp(.5f));
            c.drawRoundRect(rect,h/2,h/2,paint);paint.setStyle(Paint.Style.FILL);
        }
        void animateTo(float target) {
            if (animator != null) animator.cancel();
            if (!ValueAnimator.areAnimatorsEnabled() || !isLaidOut()) { position=target;pressScale=1;invalidate();return; }
            float initialScale=pressScale;
            animator=ValueAnimator.ofFloat(position,target);
            animator.setDuration(340);animator.setInterpolator(new OvershootInterpolator(.65f));
            animator.addUpdateListener(a->{position=(Float)a.getAnimatedValue();pressScale=1+(initialScale-1)*(1-a.getAnimatedFraction());invalidate();});
            animator.start();
        }
        void bindPosition(float value) { if(!dragging && (animator==null || !animator.isRunning())) {position=value;invalidate();} }
        void focusRing(Canvas c) {
            if (isFocused()) {
                paint.setColor(0xff007aff);paint.setStrokeWidth(dp(2));paint.setStyle(Paint.Style.STROKE);
                rect.set(dp(2),dp(2),getWidth()-dp(2),getHeight()-dp(2));c.drawRoundRect(rect,dp(18),dp(18),paint);paint.setStyle(Paint.Style.FILL);
            }
        }
        boolean begin(MotionEvent e) {
            if(!isEnabled()) return false;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN) {
                if(animator!=null)animator.cancel(); downX=e.getX();downY=e.getY();dragging=true;pressScale=1.12f;setPressed(true);invalidate();return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE && dragging) {
                float dx=Math.abs(e.getX()-downX),dy=Math.abs(e.getY()-downY);
                int slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if(dy>slop && dy>dx) {cancelTouch();return false;}
                if(dx>slop)getParent().requestDisallowInterceptTouchEvent(true);
            }
            return dragging;
        }
        void cancelTouch() {dragging=false;setPressed(false);animateTo(observedPosition());}
        abstract float observedPosition();
        @Override public boolean performClick() {super.performClick();return true;}
        @Override protected void onDetachedFromWindow(){if(animator!=null)animator.cancel();super.onDetachedFromWindow();}
    }

    static final class GlassSwitch extends Control {
        private Boolean checked;
        GlassSwitch(Context c,boolean dark,String label,Change change) {super(c,dark);listener=change;setContentDescription(label);}
        void bind(Boolean value,boolean enabled) {
            checked=value;setEnabled(enabled);setAlpha(enabled?1f:.42f);
            if(!enabled&&dragging)cancelTouch();
            setStateDescription(value==null?"尚未读取":value?"已开启":"已关闭");bindPosition(observedPosition());
        }
        @Override float observedPosition(){return Boolean.TRUE.equals(checked)?1:0;}
        @Override protected void onMeasure(int ws,int hs){setMeasuredDimension(resolveSize((int)dp(76),ws),resolveSize((int)dp(48),hs));}
        @Override protected void onDraw(Canvas c) {
            float left=(getWidth()-dp(64))/2,top=(getHeight()-dp(28))/2;
            pill(c,left,top,left+dp(64),top+dp(28),position>=.5f&&checked!=null?accent:track);
            float x=left+dp(2)+dp(21)*Math.max(0,Math.min(1,position));glass(c,x,top+dp(2),x+dp(39),top+dp(26));focusRing(c);
        }
        private void submit(boolean value) {
            if(!isEnabled()||checked==null)return;
            float releasePosition=position;
            if(value!=checked) listener.changed(value?1:0);
            // Confirmation remains the observed value. A pending label belongs to the row.
            position=releasePosition;
            animateTo(observedPosition());sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if(!begin(e))return false;
            switch(e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:position=Math.max(0,Math.min(1,observedPosition()+(e.getX()-downX)/dp(21)));invalidate();return true;
                case MotionEvent.ACTION_UP:
                    boolean tap=Math.abs(e.getX()-downX)<ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    dragging=false;setPressed(false);if(tap)performClick();submit(tap?!Boolean.TRUE.equals(checked):position>=.5f);return true;
                case MotionEvent.ACTION_CANCEL:cancelTouch();return true;
                default:return true;
            }
        }
        @Override public boolean onKeyDown(int key,KeyEvent e) {
            if(key==KeyEvent.KEYCODE_DPAD_LEFT){submit(false);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT){submit(true);return true;}
            if(key==KeyEvent.KEYCODE_SPACE||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER){submit(!Boolean.TRUE.equals(checked));return true;}
            return super.onKeyDown(key,e);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo n) {
            super.onInitializeAccessibilityNodeInfo(n);n.setClassName("android.widget.Switch");n.setCheckable(checked!=null);n.setChecked(Boolean.TRUE.equals(checked));n.setClickable(isEnabled());
            if(isEnabled())n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        }
        @Override public boolean performAccessibilityAction(int action,Bundle args) {
            if(action==AccessibilityNodeInfo.ACTION_CLICK&&isEnabled()){submit(!Boolean.TRUE.equals(checked));return true;}
            return super.performAccessibilityAction(action,args);
        }
    }

    static final class NoiseSelector extends Control {
        private int[] values={EarbudCommands.MODE_TRANSPARENCY,EarbudCommands.MODE_ANC};
        private String[] names={"通透","降噪"};
        private Integer observed;
        private final Drawable anc,transparency,adaptive;
        NoiseSelector(Context c,boolean dark,Change change) {
            super(c,dark);listener=change;setContentDescription("噪声控制");
            anc=icon(c,"lp_noise_cancellation");transparency=icon(c,"lp_transparency");adaptive=icon(c,"lp_adaptive");
        }
        private Drawable icon(Context c,String name) {int id=getResources().getIdentifier(name,"drawable",c.getPackageName());Drawable d=id==0?null:c.getDrawable(id);if(d!=null)d=d.mutate();return d;}
        void resetCapabilities() {observed=null;}
        void bind(Integer current,boolean enabled,boolean pending,boolean adaptiveSeen,boolean offSeen) {
            int count=2+(adaptiveSeen?1:0)+(offSeen?1:0);
            int[] next=new int[count];String[] labels=new String[count];int i=0;
            if(offSeen){next[i]=EarbudCommands.MODE_OFF;labels[i++]="关闭";}
            next[i]=EarbudCommands.MODE_TRANSPARENCY;labels[i++]="通透";next[i]=EarbudCommands.MODE_ANC;labels[i++]="降噪";
            if(adaptiveSeen){next[i]=EarbudCommands.MODE_ADAPTIVE;labels[i]="自适应";}
            values=next;names=labels;observed=current;setEnabled(enabled);setAlpha(enabled?1:.48f);
            if(!enabled&&dragging)cancelTouch();
            int index=observedIndex();
            String label=EarbudUiPolicy.listeningModeLabel(current,values,names);
            if(pending)label+="，请求等待耳机确认";
            setStateDescription(label);
            bindPosition(index>=0?index:position);invalidate();
        }
        private int observedIndex(){for(int i=0;i<values.length;i++)if(observed!=null&&values[i]==observed)return i;return -1;}
        @Override float observedPosition(){int index=observedIndex();return index>=0?index:position;}
        @Override protected void onMeasure(int ws,int hs){setMeasuredDimension(resolveSize((int)dp(360),ws),resolveSize((int)dp(94),hs));}
        @Override protected void onDraw(Canvas c) {
            float h=dp(60),width=getWidth(),segment=width/values.length;
            pill(c,0,0,width,h,track);
            if((observed!=null&&observedIndex()>=0)||dragging) {float x=Math.max(0,Math.min(values.length-1,position))*segment;glass(c,x+dp(3),dp(3),x+segment-dp(3),h-dp(3));}
            for(int i=0;i<values.length;i++) {
                float center=segment*(i+.5f);
                Drawable d=values[i]==EarbudCommands.MODE_ANC?anc:values[i]==EarbudCommands.MODE_TRANSPARENCY?transparency:values[i]==EarbudCommands.MODE_ADAPTIVE?adaptive:null;
                if(d!=null){d.setColorFilter(ink,PorterDuff.Mode.SRC_IN);d.setBounds((int)(center-dp(20)),(int)dp(10),(int)(center+dp(20)),(int)dp(50));d.draw(c);}
                else{paint.setColor(ink);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.8f));c.drawCircle(center,dp(30),dp(11),paint);c.drawLine(center-dp(8),dp(38),center+dp(8),dp(22),paint);paint.setStyle(Paint.Style.FILL);}
                paint.setColor(ink);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(dp(12));c.drawText(names[i],center,dp(83),paint);
            }
            focusRing(c);
        }
        private void submit(int index) {if(!isEnabled())return;index=Math.max(0,Math.min(values.length-1,index));float releasePosition=position;if(observed==null||values[index]!=observed)listener.changed(values[index]);position=releasePosition;animateTo(observedPosition());sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);}
        @Override public boolean onTouchEvent(MotionEvent e) {
            if(!begin(e))return false;
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){position=Math.max(0,Math.min(values.length-1,e.getX()/Math.max(1,getWidth())*values.length-.5f));invalidate();}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){dragging=false;setPressed(false);performClick();submit(EarbudUiPolicy.nearestSegment(e.getX(),getWidth(),values.length));}
            if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)cancelTouch();return true;
        }
        @Override public boolean onKeyDown(int key,KeyEvent e) {
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){submit(EarbudUiPolicy.arrowTarget(observedIndex(),values.length,key==KeyEvent.KEYCODE_DPAD_LEFT));return true;}
            return super.onKeyDown(key,e);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo n) {
            super.onInitializeAccessibilityNodeInfo(n);n.setClassName("android.widget.SeekBar");
            int index=observedIndex();
            if(index>=0)n.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,values.length-1,index));
            if(isEnabled())for(int i=0;i<names.length;i++)n.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01000000+i,"切换到"+names[i]));
        }
        @Override public boolean performAccessibilityAction(int action,Bundle args) {
            if(action>=0x01000000&&action<0x01000000+values.length&&isEnabled()){submit(action-0x01000000);return true;}
            return super.performAccessibilityAction(action,args);
        }
    }

    static final class VolumeSlider extends Control {
        private int current,max=1;
        VolumeSlider(Context c,boolean dark,Change change){super(c,dark);listener=change;setContentDescription("此 Android 设备的媒体音量");}
        void bind(int value,int maximum,boolean enabled){current=value;max=Math.max(1,maximum);setEnabled(enabled);setAlpha(enabled?1:.45f);setStateDescription(Math.round(100f*current/max)+"%");bindPosition(observedPosition());}
        @Override float observedPosition(){return (float)current/max;}
        @Override protected void onMeasure(int ws,int hs){setMeasuredDimension(resolveSize((int)dp(320),ws),resolveSize((int)dp(48),hs));}
        @Override protected void onDraw(Canvas c){float x=dp(22)+Math.max(0,Math.min(1,position))*(getWidth()-dp(44)),y=getHeight()/2f;pill(c,dp(4),y-dp(3),getWidth()-dp(4),y+dp(3),track);pill(c,dp(4),y-dp(3),x,y+dp(3),0xff99999f);glass(c,x-dp(19),y-dp(12),x+dp(19),y+dp(12));focusRing(c);}
        private void submit(int value){if(!isEnabled())return;listener.changed(Math.max(0,Math.min(max,value)));sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);}
        @Override public boolean onTouchEvent(MotionEvent e){if(!begin(e))return false;
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE||e.getActionMasked()==MotionEvent.ACTION_UP){position=Math.max(0,Math.min(1,(e.getX()-dp(22))/Math.max(1,getWidth()-dp(44))));invalidate();}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){dragging=false;setPressed(false);performClick();submit(Math.round(position*max));animateTo(position);}
            if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)cancelTouch();return true;}
        @Override public boolean onKeyDown(int key,KeyEvent e){if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){submit(current+(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1));return true;}return super.onKeyDown(key,e);}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo n){super.onInitializeAccessibilityNodeInfo(n);n.setClassName("android.widget.SeekBar");n.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,max,current));if(isEnabled()){n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}}
        @Override public boolean performAccessibilityAction(int action,Bundle args){if(isEnabled()){
            if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&args!=null){submit(Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)));return true;}
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){submit(current+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1));return true;}
        }return super.performAccessibilityAction(action,args);}
    }
}
