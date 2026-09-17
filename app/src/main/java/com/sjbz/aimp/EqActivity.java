package com.sjbz.aimp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.sjbz.aimp.audio.GlobalAudioSessionManager;
import com.sjbz.aimp.audio.SjbzAudioEngine;
import com.sjbz.aimp.audio.SjbzDspProcessor;
import com.sjbz.aimp.service.GlobalAudioService;
import com.sjbz.aimp.ui.AudioSpectrumVisualizerView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class EqActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "sjbz_dsp_pro_prefs";
    private SjbzDspProcessor dspProcessor;
    private GlobalAudioSessionManager audioSessionManager;
    private SharedPreferences prefs;
    private boolean firstPresetLoad = true;

    private Toolbar eqToolbar;
    private SwitchCompat switchMasterDsp, switchMasterDspSection, switchBassBoost, switchHardwareEmulation, switchBluetoothBypass, switchMdrc, switchGlobalAudioMode;
    private LinearLayout bannerGlobalBypass, headerMaster, contentMaster, headerPreGains, contentPreGains, headerEq32, contentEq32, headerDynamics, contentDynamics, headerPresets, contentPresets, llFadersContainer;
    private ImageView chevronMaster, chevronPreGains, chevronEq32, chevronDynamics, chevronPresets;
    private AudioSpectrumVisualizerView visualizerView;
    private SeekBar seekPreamp, seekPreGainBass, seekPreGainMid, seekPreGainTreble, seekBassGain, seekEmulationAmount, seekMdrcThreshold, seekMdrcRatio, seekMdrcSub, seekMdrcLow, seekMdrcMid, seekMdrcHigh, seekMdrcAir;
    private TextView tvPreampValue, tvEffectivePreamp, tvClippingIndicator, tvCaptureSessionCount, tvPreGainBassValue, tvPreGainMidValue, tvPreGainTrebleValue, tvBassGainValue, tvEmulationAmount, tvBluetoothStatus, tvMdrcGrMeter, tvMdrcThresholdValue, tvMdrcRatioValue, tvMdrcSub, tvMdrcLow, tvMdrcMid, tvMdrcHigh, tvMdrcAir, tvDetectedGenre, tvDetectedGenreConfidence;
    private Button btnResetPreamp, btnResetPreGains, btnResetEq, btnPresetFlat, btnPresetBass, btnPresetRock, btnPresetVocal, btnSaveCustomPreset, btnApplyGenreEq;
    private Spinner spinnerBassFreq, spinnerPresets;
    private HorizontalScrollView scrollFaders; private ScrollView scrollEqRoot;
    private final SeekBar[] eqSeekBars = new SeekBar[32];

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable clippingResetRunnable = () -> { if(tvClippingIndicator!=null){ tvClippingIndicator.setText("HEADROOM ÓPTIMO"); tvClippingIndicator.setTextColor(Color.parseColor("#00E5FF")); } };
    private final Runnable mdrcMeterRunnable = new Runnable(){ public void run(){ if(dspProcessor!=null&&tvMdrcGrMeter!=null) tvMdrcGrMeter.setText(String.format(Locale.US,"GR: -%.1f dB",dspProcessor.getMdrcGainReduction())); mainHandler.postDelayed(this,100);} };
    private final Runnable genreRunnable = new Runnable(){ public void run(){ if(dspProcessor!=null&&tvDetectedGenre!=null){ String[] g=dspProcessor.detectGenre(); tvDetectedGenre.setText(g[0]); tvDetectedGenreConfidence.setText("Confianza: "+g[1]+"%"); } mainHandler.postDelayed(this,2000);} };

    @Override protected void onCreate(@Nullable Bundle s){
        super.onCreate(s); setContentView(R.layout.activity_eq);
        prefs=getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE);
        audioSessionManager=GlobalAudioSessionManager.getInstance(this);
        initDspEngine(); bindViews(); fixSeekMax(); setupCollapsibleSections(); setup32BandFaders(); setupEventListeners(); loadSavedSettings(); updateHeadroomDisplay(); checkGlobalBypassStatus();
    }
    @Override protected void onResume(){ super.onResume(); checkGlobalBypassStatus(); mainHandler.post(mdrcMeterRunnable); mainHandler.post(genreRunnable); }
    @Override protected void onPause(){ super.onPause(); mainHandler.removeCallbacks(mdrcMeterRunnable); mainHandler.removeCallbacks(genreRunnable); saveSettings(); }

    private void initDspEngine(){
        dspProcessor=SjbzAudioEngine.getInstance().getDspProcessor();
        try{
            AudioManager am=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
            String sr=am!=null?am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE):null;
            if(sr!=null) dspProcessor.setSampleRate(Float.parseFloat(sr));
        }catch(Exception e){}
        dspProcessor.resetFilterStates();
        dspProcessor.setFftListener(samples->{ if(visualizerView!=null) visualizerView.post(()->visualizerView.onAudioData(samples)); });
        dspProcessor.setClippingListener(c->mainHandler.post(()->{ if(c&&tvClippingIndicator!=null){ tvClippingIndicator.setText("¡CLIPPING! (Limiter -1dB)"); tvClippingIndicator.setTextColor(Color.parseColor("#F43F5E")); mainHandler.removeCallbacks(clippingResetRunnable); mainHandler.postDelayed(clippingResetRunnable,600);} }));
    }

    private void fixSeekMax(){
        if(seekMdrcThreshold!=null&&seekMdrcThreshold.getMax()<36) seekMdrcThreshold.setMax(36);
        if(seekMdrcRatio!=null&&seekMdrcRatio.getMax()<30) seekMdrcRatio.setMax(30);
        if(seekBassGain!=null&&seekBassGain.getMax()<120) seekBassGain.setMax(120);
    }

    private void bindViews(){
        eqToolbar=findViewById(R.id.eqToolbar); if(eqToolbar!=null) eqToolbar.setNavigationOnClickListener(v->finish());
        switchMasterDsp=findViewById(R.id.switchMasterDsp); switchMasterDspSection=findViewById(R.id.switchMasterDspSection);
        bannerGlobalBypass=findViewById(R.id.bannerGlobalBypass); scrollEqRoot=findViewById(R.id.scrollEqRoot);
        headerMaster=findViewById(R.id.headerMaster); contentMaster=findViewById(R.id.contentMaster); chevronMaster=findViewById(R.id.chevronMaster);
        visualizerView=findViewById(R.id.visualizerView); seekPreamp=findViewById(R.id.seekPreamp); tvPreampValue=findViewById(R.id.tvPreampValue); tvEffectivePreamp=findViewById(R.id.tvEffectivePreamp); tvClippingIndicator=findViewById(R.id.tvClippingIndicator); btnResetPreamp=findViewById(R.id.btnResetPreamp); switchGlobalAudioMode=findViewById(R.id.switchGlobalAudioMode); tvCaptureSessionCount=findViewById(R.id.tvCaptureSessionCount);
        headerPreGains=findViewById(R.id.headerPreGains); contentPreGains=findViewById(R.id.contentPreGains); chevronPreGains=findViewById(R.id.chevronPreGains); btnResetPreGains=findViewById(R.id.btnResetPreGains); seekPreGainBass=findViewById(R.id.seekPreGainBass); tvPreGainBassValue=findViewById(R.id.tvPreGainBassValue); seekPreGainMid=findViewById(R.id.seekPreGainMid); tvPreGainMidValue=findViewById(R.id.tvPreGainMidValue); seekPreGainTreble=findViewById(R.id.seekPreGainTreble); tvPreGainTrebleValue=findViewById(R.id.tvPreGainTrebleValue);
        headerEq32=findViewById(R.id.headerEq32); contentEq32=findViewById(R.id.contentEq32); chevronEq32=findViewById(R.id.chevronEq32); btnResetEq=findViewById(R.id.btnResetEq); scrollFaders=findViewById(R.id.scrollFaders); llFadersContainer=findViewById(R.id.llFadersContainer);
        headerDynamics=findViewById(R.id.headerDynamics); contentDynamics=findViewById(R.id.contentDynamics); chevronDynamics=findViewById(R.id.chevronDynamics);
        switchBassBoost=findViewById(R.id.switchBassBoost); spinnerBassFreq=findViewById(R.id.spinnerBassFreq); seekBassGain=findViewById(R.id.seekBassGain); tvBassGainValue=findViewById(R.id.tvBassGainValue);
        switchHardwareEmulation=findViewById(R.id.switchHardwareEmulation); seekEmulationAmount=findViewById(R.id.seekEmulationAmount); tvEmulationAmount=findViewById(R.id.tvEmulationAmount); switchBluetoothBypass=findViewById(R.id.switchBluetoothBypass); tvBluetoothStatus=findViewById(R.id.tvBluetoothStatus);
        switchMdrc=findViewById(R.id.switchMdrc); tvMdrcGrMeter=findViewById(R.id.tvMdrcGrMeter); seekMdrcThreshold=findViewById(R.id.seekMdrcThreshold); tvMdrcThresholdValue=findViewById(R.id.tvMdrcThresholdValue); seekMdrcRatio=findViewById(R.id.seekMdrcRatio); tvMdrcRatioValue=findViewById(R.id.tvMdrcRatioValue);
        seekMdrcSub=findViewById(R.id.seekMdrcSub); tvMdrcSub=findViewById(R.id.tvMdrcSub); seekMdrcLow=findViewById(R.id.seekMdrcLow); tvMdrcLow=findViewById(R.id.tvMdrcLow); seekMdrcMid=findViewById(R.id.seekMdrcMid); tvMdrcMid=findViewById(R.id.tvMdrcMid); seekMdrcHigh=findViewById(R.id.seekMdrcHigh); tvMdrcHigh=findViewById(R.id.tvMdrcHigh); seekMdrcAir=findViewById(R.id.seekMdrcAir); tvMdrcAir=findViewById(R.id.tvMdrcAir);
        headerPresets=findViewById(R.id.headerPresets); contentPresets=findViewById(R.id.contentPresets); chevronPresets=findViewById(R.id.chevronPresets); btnPresetFlat=findViewById(R.id.btnPresetFlat); btnPresetBass=findViewById(R.id.btnPresetBass); btnPresetRock=findViewById(R.id.btnPresetRock); btnPresetVocal=findViewById(R.id.btnPresetVocal); spinnerPresets=findViewById(R.id.spinnerPresets); btnSaveCustomPreset=findViewById(R.id.btnSaveCustomPreset); tvDetectedGenre=findViewById(R.id.tvDetectedGenre); tvDetectedGenreConfidence=findViewById(R.id.tvDetectedGenreConfidence); btnApplyGenreEq=findViewById(R.id.btnApplyGenreEq);
    }

    private void setupCollapsibleSections(){ tgl(headerMaster,contentMaster,chevronMaster,true); tgl(headerPreGains,contentPreGains,chevronPreGains,true); tgl(headerEq32,contentEq32,chevronEq32,true); tgl(headerDynamics,contentDynamics,chevronDynamics,true); tgl(headerPresets,contentPresets,chevronPresets,true); }
    private void tgl(View h,View c,ImageView ch,boolean e){ c.setVisibility(e?View.VISIBLE:View.GONE); ch.setRotation(e?0f:-90f); h.setOnClickListener(v->{ boolean x=c.getVisibility()==View.VISIBLE; c.setVisibility(x?View.GONE:View.VISIBLE); ch.animate().rotation(x?-90f:0f).setDuration(200).start(); }); }

    private void setup32BandFaders(){
        llFadersContainer.removeAllViews();
        for(int i=0;i<32;i++){ final int bi=i;
            LinearLayout col=new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL); col.setPadding(8,0,8,0);
            TextView tg=new TextView(this); tg.setTextSize(10f); tg.setTextColor(Color.parseColor("#38BDF8")); tg.setText("0.0"); tg.setGravity(Gravity.CENTER); col.addView(tg);
            FrameLayout f=new FrameLayout(this); f.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(48),dpToPx(190)));
            SeekBar sb=new SeekBar(this); FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dpToPx(190),dpToPx(48)); lp.gravity=Gravity.CENTER; sb.setLayoutParams(lp); sb.setRotation(270f); sb.setMax(240); sb.setProgress(120);
            sb.setOnTouchListener((v,ev)->{ boolean d=ev.getAction()==MotionEvent.ACTION_DOWN||ev.getAction()==MotionEvent.ACTION_MOVE; if(scrollFaders!=null)scrollFaders.requestDisallowInterceptTouchEvent(d); if(scrollEqRoot!=null)scrollEqRoot.requestDisallowInterceptTouchEvent(d); return false; });
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean fr){ float g=(p-120)/10f; tg.setText(String.format(Locale.US,"%+.1f",g)); dspProcessor.setBandLevel(bi,g); if(audioSessionManager!=null)audioSessionManager.setBandGain(bi,g);} public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
            f.addView(sb); col.addView(f);
            TextView tf=new TextView(this); tf.setTextSize(10f); tf.setTextColor(Color.parseColor("#94A3B8")); tf.setText(SjbzDspProcessor.BAND_LABELS[i]); tf.setGravity(Gravity.CENTER); col.addView(tf);
            llFadersContainer.addView(col); eqSeekBars[i]=sb;
        }
    }

    private void setupEventListeners(){
        switchMasterDsp.setOnCheckedChangeListener((b,c)->{ dspProcessor.setMasterEnabled(c); if(switchMasterDspSection.isChecked()!=c)switchMasterDspSection.setChecked(c); saveSettings(); });
        switchMasterDspSection.setOnCheckedChangeListener((b,c)->{ dspProcessor.setMasterEnabled(c); if(switchMasterDsp.isChecked()!=c)switchMasterDsp.setChecked(c); saveSettings(); });
        seekPreamp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean f){ float v=(p-120)/10f; tvPreampValue.setText(String.format(Locale.US,"%+.1f dB",v)); dspProcessor.setPreamp(v); if(audioSessionManager!=null)audioSessionManager.setPreampGain(v); updateHeadroom();} public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
        btnResetPreamp.setOnClickListener(v->{ seekPreamp.setProgress(120); dspProcessor.setPreamp(0f); updateHeadroom(); saveSettings(); });
        switchGlobalAudioMode.setOnCheckedChangeListener((b,c)->{ if(c)GlobalAudioService.Companion.start(this); else GlobalAudioService.Companion.stop(this); mainHandler.postDelayed(()->checkGlobalBypassStatus(),300); });
        seekPreGainBass.setOnSeekBarChangeListener(gl(v->dspProcessor.setBassGain(v),tvPreGainBassValue));
        seekPreGainMid.setOnSeekBarChangeListener(gl(v->dspProcessor.setMidGain(v),tvPreGainMidValue));
        seekPreGainTreble.setOnSeekBarChangeListener(gl(v->dspProcessor.setTrebleGain(v),tvPreGainTrebleValue));
        btnResetPreGains.setOnClickListener(v->{ seekPreGainBass.setProgress(120); seekPreGainMid.setProgress(120); seekPreGainTreble.setProgress(120); dspProcessor.setBassGain(0f); dspProcessor.setMidGain(0f); dspProcessor.setTrebleGain(0f); saveSettings(); });
        btnResetEq.setOnClickListener(v->{ for(int i=0;i<32;i++){ eqSeekBars[i].setProgress(120); dspProcessor.setBandLevel(i,0f); if(audioSessionManager!=null)audioSessionManager.setBandGain(i,0f);} saveSettings(); });
        switchBassBoost.setOnCheckedChangeListener((b,c)->{ dspProcessor.setBassBoost(c,dspProcessor.getBassBoostFreq(),dspProcessor.getBassBoostGain()); updateHeadroom(); saveSettings(); });
        float[] bf={60f,85f,120f}; spinnerBassFreq.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"60 Hz (Sub)","85 Hz (Punch)","120 Hz (Mid-Bass)"})); spinnerBassFreq.setSelection(1);
        spinnerBassFreq.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){ public void onItemSelected(AdapterView<?>p,View v,int pos,long id){ dspProcessor.setBassBoost(switchBassBoost.isChecked(),bf[pos],dspProcessor.getBassBoostGain()); saveSettings();} public void onNothingSelected(AdapterView<?>p){} });
        seekBassGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean f){ float g=p/10f; tvBassGainValue.setText(String.format(Locale.US,"+%.1f dB",g)); dspProcessor.setBassBoost(switchBassBoost.isChecked(),dspProcessor.getBassBoostFreq(),g); if(audioSessionManager!=null)audioSessionManager.setBassBoostGain(g); updateHeadroom();} public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
        switchHardwareEmulation.setOnCheckedChangeListener((b,c)->{ dspProcessor.setEmulationEnabled(c); updateHeadroom(); updateBt(); saveSettings(); });
        seekEmulationAmount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean f){ tvEmulationAmount.setText(p+"%"); dspProcessor.setEmulationAmount(p/100f); updateHeadroom();} public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
        switchBluetoothBypass.setOnCheckedChangeListener((b,c)->{ dspProcessor.setBluetoothAutoBypass(c); updateHeadroom(); updateBt(); saveSettings(); });
        switchMdrc.setOnCheckedChangeListener((b,c)->{ dspProcessor.setMdrcEnabled(c); if(audioSessionManager!=null)audioSessionManager.setMdrcEnabled(c); saveSettings(); });
        seekMdrcThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean f){ float t=-36f+p; tvMdrcThresholdValue.setText(String.format(Locale.US,"Umbral: %.0f dB",t)); dspProcessor.setMdrcDynamics(t,dspProcessor.getMdrcRatio());} public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
        seekMdrcRatio.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean f){ float r=1f+p/10f; tvMdrcRatioValue.setText(String.format(Locale.US,"Ratio: %.1f:1",r)); dspProcessor.setMdrcDynamics(dspProcessor.getMdrcThreshold(),r);} public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
        sm(seekMdrcSub,tvMdrcSub,0); sm(seekMdrcLow,tvMdrcLow,1); sm(seekMdrcMid,tvMdrcMid,2); sm(seekMdrcHigh,tvMdrcHigh,3); sm(seekMdrcAir,tvMdrcAir,4);
        setupPresets(); updateBt();
    }
    interface GA{void a(float g);} private SeekBar.OnSeekBarChangeListener gl(GA a,TextView tv){ return new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean f){ float g=(p-120)/10f; tv.setText(String.format(Locale.US,"%+.1f dB",g)); a.a(g);} public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){ saveSettings(); }}; }
    private void sm(SeekBar sb,TextView tv,int b){ sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean f){ float g=(p-120)/10f; tv.setText(String.format(Locale.US,"%+.1f dB",g)); dspProcessor.setMdrcBandGain(b,g); if(audioSessionManager!=null)audioSessionManager.setMdrcBandGain(b,g);} public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){ saveSettings(); }}); }

    private void setupPresets(){
        btnPresetFlat.setOnClickListener(v->applyCurve(new float[32]));
        btnPresetBass.setOnClickListener(v->{ float[] c=new float[32]; for(int i=0;i<=10;i++)c[i]=5f-i*0.45f; applyCurve(c); seekBassGain.setProgress(80); });
        btnPresetRock.setOnClickListener(v->{ float[] c=new float[32]; c[0]=4f;c[1]=4f;c[2]=3.5f;c[16]=-1f;c[17]=-1.5f;c[28]=3.5f;c[29]=4f; applyCurve(c); });
        btnPresetVocal.setOnClickListener(v->{ float[] c=new float[32]; c[0]=-3f;c[1]=-3f;c[17]=3f;c[18]=3.5f; applyCurve(c); });
        List<String> names=new ArrayList<>(Arrays.asList("Studio Master (Flat)","Bass Heavy Sub","Rock & Roll V-Shape","Acoustic / Vocal","Electronic & EDM"));
        for(String k:prefs.getAll().keySet()) if(k.startsWith("custom_preset_")){ String n=k.replace("custom_preset_","").replace("_bands",""); if(!names.contains(n)) names.add(n); }
        ArrayAdapter<String> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names); spinnerPresets.setAdapter(ad);
        firstPresetLoad=true;
        spinnerPresets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?>p,View v,int pos,long id){
                if(firstPresetLoad){ firstPresetLoad=false; return; }
                String n=names.get(pos);
                if(n.contains("Flat")) btnPresetFlat.performClick(); else if(n.contains("Bass Heavy")) btnPresetBass.performClick();
                else if(n.contains("Rock")) btnPresetRock.performClick(); else if(n.contains("Acoustic")) btnPresetVocal.performClick();
                else if(n.contains("Electronic")){ float[] e=new float[32]; e[0]=5f;e[31]=4f; applyCurve(e); } else loadCustom(n);
            } public void onNothingSelected(AdapterView<?>p){} });
        btnSaveCustomPreset.setOnClickListener(v->{ AlertDialog.Builder b=new AlertDialog.Builder(this); b.setTitle("Guardar Preset"); EditText in=new EditText(this); b.setView(in); b.setPositiveButton("Guardar",(d,w)->{ String n=in.getText().toString().trim(); if(n.isEmpty())return; StringBuilder sb=new StringBuilder(); for(int i=0;i<32;i++){ if(i>0)sb.append(","); sb.append(dspProcessor.getBandGain(i)); } prefs.edit().putString("custom_preset_"+n+"_bands",sb.toString()).apply(); if(!names.contains(n)){ names.add(n); ad.notifyDataSetChanged(); } firstPresetLoad=false; spinnerPresets.setSelection(names.indexOf(n)); }); b.setNegativeButton("Cancelar",null); b.show(); });
        btnApplyGenreEq.setOnClickListener(v->{ String[] g=dspProcessor.detectGenre(); tvDetectedGenre.setText(g[0]); tvDetectedGenreConfidence.setText("Confianza: "+g[1]+"%"); if(g[0].contains("Bass")||g[0].contains("Hip")) btnPresetBass.performClick(); else if(g[0].contains("Vocal")) btnPresetVocal.performClick(); else btnPresetRock.performClick(); Toast.makeText(this,"Aplicado: "+g[0],Toast.LENGTH_SHORT).show(); });
    }
    private void applyCurve(float[] c){ for(int i=0;i<32;i++){ int pr=Math.round(c[i]*10f+120f); if(eqSeekBars[i]!=null)eqSeekBars[i].setProgress(pr); dspProcessor.setBandLevel(i,c[i]); if(audioSessionManager!=null)audioSessionManager.setBandGain(i,c[i]); } saveSettings(); }
    private void loadCustom(String n){ String d=prefs.getString("custom_preset_"+n+"_bands",null); if(d==null)return; String[] ps=d.split(","); float[] c=new float[32]; for(int i=0;i<Math.min(32,ps.length);i++) try{c[i]=Float.parseFloat(ps[i]);}catch(Exception e){} applyCurve(c); }

    private void updateHeadroom(){ if(dspProcessor!=null&&tvEffectivePreamp!=null) tvEffectivePreamp.setText(String.format(Locale.US,"Auto-Headroom: %+.1f dB",dspProcessor.getEffectivePreampDb())); }
    private void updateBt(){ if(tvBluetoothStatus==null)return; boolean by=switchBluetoothBypass!=null&&switchBluetoothBypass.isChecked(); tvBluetoothStatus.setText(by?"BT Bypass: ON (EMU bypaseada en BT)":"BT Bypass: OFF"); }
    private void checkGlobalBypassStatus(){
        boolean r=false; try{ r=GlobalAudioService.Companion.isServiceRunning()||GlobalAudioService.isGlobalAudioEnabled()||(audioSessionManager!=null&&audioSessionManager.isGlobalAudioEnabled()); }catch(Exception e){}
        dspProcessor.setGlobalBypass(false);
        if(bannerGlobalBypass!=null)bannerGlobalBypass.setVisibility(r?View.VISIBLE:View.GONE);
        if(switchGlobalAudioMode!=null)switchGlobalAudioMode.setChecked(r);
        if(tvCaptureSessionCount!=null&&audioSessionManager!=null)tvCaptureSessionCount.setText("Sesión: "+audioSessionManager.getActiveSessionsCount()+" activas");
        updateBt();
    }
    private void loadSavedSettings(){
        boolean m=prefs.getBoolean("master_enabled",true); switchMasterDsp.setChecked(m); switchMasterDspSection.setChecked(m); dspProcessor.setMasterEnabled(m);
        float pre=prefs.getFloat("preamp_db",0f); seekPreamp.setProgress(Math.round(pre*10f+120f)); dspProcessor.setPreamp(pre); if(audioSessionManager!=null)audioSessionManager.setPreampGain(pre);
        float pb=prefs.getFloat("pregain_bass_db",0f); seekPreGainBass.setProgress(Math.round(pb*10f+120f)); dspProcessor.setBassGain(pb);
        float pm=prefs.getFloat("pregain_mid_db",0f); seekPreGainMid.setProgress(Math.round(pm*10f+120f)); dspProcessor.setMidGain(pm);
        float pt=prefs.getFloat("pregain_treble_db",0f); seekPreGainTreble.setProgress(Math.round(pt*10f+120f)); dspProcessor.setTrebleGain(pt);
        for(int i=0;i<32;i++){ float g=prefs.getFloat("eq_band_"+i,0f); if(eqSeekBars[i]!=null)eqSeekBars[i].setProgress(Math.round(g*10f+120f)); dspProcessor.setBandLevel(i,g); if(audioSessionManager!=null)audioSessionManager.setBandGain(i,g); }
        boolean bb=prefs.getBoolean("bassboost_enabled",true); float bg=prefs.getFloat("bassboost_gain",4f); switchBassBoost.setChecked(bb); seekBassGain.setProgress(Math.round(bg*10f)); dspProcessor.setBassBoost(bb,85f,bg); if(audioSessionManager!=null)audioSessionManager.setBassBoostGain(bg);
        boolean emu=prefs.getBoolean("emu_enabled",true); float ea=prefs.getFloat("emu_amount",0.8f); switchHardwareEmulation.setChecked(emu); seekEmulationAmount.setProgress(Math.round(ea*100f)); dspProcessor.setEmulationEnabled(emu); dspProcessor.setEmulationAmount(ea);
        boolean mdrc=prefs.getBoolean("mdrc_enabled",true); switchMdrc.setChecked(mdrc); dspProcessor.setMdrcEnabled(mdrc); if(audioSessionManager!=null)audioSessionManager.setMdrcEnabled(mdrc);
    }
    private void saveSettings(){
        if(prefs==null||dspProcessor==null)return; SharedPreferences.Editor e=prefs.edit();
        e.putBoolean("master_enabled",dspProcessor.isMasterEnabled()); e.putFloat("preamp_db",dspProcessor.getPreamp());
        e.putFloat("pregain_bass_db",dspProcessor.getBassGain()); e.putFloat("pregain_mid_db",dspProcessor.getMidGain()); e.putFloat("pregain_treble_db",dspProcessor.getTrebleGain());
        for(int i=0;i<32;i++)e.putFloat("eq_band_"+i,dspProcessor.getBandGain(i));
        e.putBoolean("bassboost_enabled",dspProcessor.isBassBoostEnabled()); e.putFloat("bassboost_gain",dspProcessor.getBassBoostGain());
        e.putBoolean("emu_enabled",dspProcessor.isEmulationEnabled()); e.putFloat("emu_amount",dspProcessor.getEmulationAmount());
        e.putBoolean("mdrc_enabled",dspProcessor.isMdrcEnabled()); e.apply();
    }
    private int dpToPx(int dp){ return Math.round(dp*getResources().getDisplayMetrics().density); }
}
