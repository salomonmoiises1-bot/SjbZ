package com.sjbz.aimp;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
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
    private static final String KEY_MASTER_ENABLED = "master_enabled";
    private static final String KEY_PREAMP = "preamp_db";
    private static final String KEY_PREGAIN_BASS = "pregain_bass_db";
    private static final String KEY_PREGAIN_MID = "pregain_mid_db";
    private static final String KEY_PREGAIN_TREBLE = "pregain_treble_db";
    private static final String KEY_BASSBOOST_ENABLED = "bassboost_enabled";
    private static final String KEY_BASSBOOST_GAIN = "bassboost_gain";
    private static final String KEY_EMU_ENABLED = "emu_enabled";
    private static final String KEY_EMU_AMOUNT = "emu_amount";
    private static final String KEY_MDRC_ENABLED = "mdrc_enabled";

    private SjbzDspProcessor dspProcessor;
    private GlobalAudioSessionManager audioSessionManager;
    private SharedPreferences prefs;

    private Toolbar eqToolbar;
    private SwitchCompat switchMasterDsp, switchMasterDspSection, switchBassBoost, switchHardwareEmulation, switchBluetoothBypass, switchMdrc, switchGlobalAudioMode;
    private LinearLayout bannerGlobalBypass, headerMaster, contentMaster, headerPreGains, contentPreGains, headerEq32, contentEq32, headerDynamics, contentDynamics, headerPresets, contentPresets, llFadersContainer;
    private ImageView chevronMaster, chevronPreGains, chevronEq32, chevronDynamics, chevronPresets;
    private AudioSpectrumVisualizerView visualizerView;
    private SeekBar seekPreamp, seekPreGainBass, seekPreGainMid, seekPreGainTreble, seekBassGain, seekEmulationAmount, seekMdrcThreshold, seekMdrcRatio, seekMdrcSub, seekMdrcLow, seekMdrcMid, seekMdrcHigh, seekMdrcAir;
    private TextView tvPreampValue, tvEffectivePreamp, tvClippingIndicator, tvCaptureSessionCount, tvPreGainBassValue, tvPreGainMidValue, tvPreGainTrebleValue, tvBassGainValue, tvEmulationAmount, tvBluetoothStatus, tvMdrcGrMeter, tvMdrcThresholdValue, tvMdrcRatioValue, tvMdrcSub, tvMdrcLow, tvMdrcMid, tvMdrcHigh, tvMdrcAir, tvDetectedGenre, tvDetectedGenreConfidence;
    private Button btnResetPreamp, btnResetPreGains, btnResetEq, btnPresetFlat, btnPresetBass, btnPresetRock, btnPresetVocal, btnSaveCustomPreset, btnApplyGenreEq;
    private Spinner spinnerBassFreq, spinnerPresets;
    private HorizontalScrollView scrollFaders;
    private ScrollView scrollEqRoot;
    private final SeekBar[] eqSeekBars = new SeekBar[SjbzDspProcessor.BAND_COUNT];
    private final TextView[] eqValueTexts = new TextView[SjbzDspProcessor.BAND_COUNT];

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable clippingResetRunnable = () -> { if(tvClippingIndicator!=null){ tvClippingIndicator.setText("HEADROOM ÓPTIMO"); tvClippingIndicator.setTextColor(Color.parseColor("#00E5FF")); tvClippingIndicator.setBackgroundColor(Color.parseColor("#2000E5FF")); } };
    private final Runnable mdrcMeterRunnable = new Runnable(){ @Override public void run(){ if(dspProcessor!=null&&tvMdrcGrMeter!=null) tvMdrcGrMeter.setText(String.format(Locale.US,"GR: -%.1f dB",dspProcessor.getMdrcGainReduction())); mainHandler.postDelayed(this,100);} };
    private final Runnable genreUpdateRunnable = new Runnable(){ @Override public void run(){ if(dspProcessor!=null&&tvDetectedGenre!=null){ String[] g=dspProcessor.detectGenre(); tvDetectedGenre.setText(g[0]); tvDetectedGenreConfidence.setText("Confianza: "+g[1]+"%"); } mainHandler.postDelayed(this,2000);} };

    @Override protected void onCreate(@Nullable Bundle s){ super.onCreate(s); setContentView(R.layout.activity_eq); prefs=getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE); audioSessionManager=GlobalAudioSessionManager.getInstance(this); initDspEngine(); bindViews(); setupCollapsibleSections(); setup32BandFaders(); setupEventListeners(); loadSavedSettings(); updateHeadroomDisplay(); checkGlobalBypassStatus(); }
    @Override protected void onResume(){ super.onResume(); checkGlobalBypassStatus(); mainHandler.post(mdrcMeterRunnable); mainHandler.post(genreUpdateRunnable); }
    @Override protected void onPause(){ super.onPause(); mainHandler.removeCallbacks(mdrcMeterRunnable); mainHandler.removeCallbacks(genreUpdateRunnable); saveSettings(); }

    private void initDspEngine(){
        dspProcessor=SjbzAudioEngine.getInstance().getDspProcessor();
        dspProcessor.resetFilterStates();
        dspProcessor.setFftListener(samples->{ if(visualizerView!=null) visualizerView.post(()->visualizerView.onAudioData(samples)); });
        dspProcessor.setClippingListener(isClipping->mainHandler.post(()->{ if(isClipping&&tvClippingIndicator!=null){ tvClippingIndicator.setText("¡CLIPPING DETECTADO! (Limiter -1dB)"); tvClippingIndicator.setTextColor(Color.parseColor("#F43F5E")); tvClippingIndicator.setBackgroundColor(Color.parseColor("#40F43F5E")); mainHandler.removeCallbacks(clippingResetRunnable); mainHandler.postDelayed(clippingResetRunnable,600);} }));
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

    private void setupCollapsibleSections(){ setupSectionToggle(headerMaster,contentMaster,chevronMaster,true); setupSectionToggle(headerPreGains,contentPreGains,chevronPreGains,true); setupSectionToggle(headerEq32,contentEq32,chevronEq32,true); setupSectionToggle(headerDynamics,contentDynamics,chevronDynamics,true); setupSectionToggle(headerPresets,contentPresets,chevronPresets,true); }
    private void setupSectionToggle(View h,View c,ImageView ch,boolean exp){ c.setVisibility(exp?View.VISIBLE:View.GONE); ch.setRotation(exp?0f:-90f); h.setOnClickListener(v->{ boolean e=c.getVisibility()==View.VISIBLE; c.setVisibility(e?View.GONE:View.VISIBLE); ch.animate().rotation(e?-90f:0f).setDuration(200).start(); }); }

    private void setup32BandFaders(){
        llFadersContainer.removeAllViews();
        for(int i=0;i<SjbzDspProcessor.BAND_COUNT;i++){
            final int bi=i; String label=SjbzDspProcessor.BAND_LABELS[i];
            LinearLayout col=new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL); col.setPadding(8,0,8,0);
            TextView tvGain=new TextView(this); tvGain.setTextSize(10f); tvGain.setTextColor(Color.parseColor("#38BDF8")); tvGain.setText("0.0"); tvGain.setGravity(Gravity.CENTER); eqValueTexts[i]=tvGain; col.addView(tvGain);
            FrameLayout f=new FrameLayout(this); f.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(48),dpToPx(190)));
            SeekBar sb=new SeekBar(this); FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dpToPx(190),dpToPx(48)); lp.gravity=Gravity.CENTER; sb.setLayoutParams(lp); sb.setRotation(270f); sb.setMax(240); sb.setProgress(120);
            sb.setOnTouchListener((v,e)->{ int a=e.getAction(); if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_MOVE){ if(scrollFaders!=null)scrollFaders.requestDisallowInterceptTouchEvent(true); if(scrollEqRoot!=null)scrollEqRoot.requestDisallowInterceptTouchEvent(true);} else { if(scrollFaders!=null)scrollFaders.requestDisallowInterceptTouchEvent(false); if(scrollEqRoot!=null)scrollEqRoot.requestDisallowInterceptTouchEvent(false);} return false; });
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ @Override public void onProgressChanged(SeekBar s,int p,boolean f){ float g=(p-120)/10f; tvGain.setText(String.format(Locale.US,"%+.1f",g)); dspProcessor.setBandLevel(bi,g); if(audioSessionManager!=null)audioSessionManager.setBandGain(bi,g);} @Override public void onStartTrackingTouch(SeekBar s){} @Override public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
            f.addView(sb); col.addView(f);
            TextView tf=new TextView(this); tf.setTextSize(10f); tf.setTextColor(Color.parseColor("#94A3B8")); tf.setText(label); tf.setGravity(Gravity.CENTER); col.addView(tf);
            llFadersContainer.addView(col); eqSeekBars[i]=sb;
        }
    }

    private void setupEventListeners(){
        switchMasterDsp.setOnCheckedChangeListener((b,c)->{ dspProcessor.setMasterEnabled(c); if(switchMasterDspSection.isChecked()!=c)switchMasterDspSection.setChecked(c); saveSettings(); });
        switchMasterDspSection.setOnCheckedChangeListener((b,c)->{ dspProcessor.setMasterEnabled(c); if(switchMasterDsp.isChecked()!=c)switchMasterDsp.setChecked(c); saveSettings(); });
        seekPreamp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ @Override public void onProgressChanged(SeekBar s,int p,boolean f){ float v=(p-120)/10f; tvPreampValue.setText(String.format(Locale.US,"%+.1f dB",v)); dspProcessor.setPreamp(v); if(audioSessionManager!=null)audioSessionManager.setPreampGain(v); updateHeadroomDisplay();} @Override public void onStartTrackingTouch(SeekBar s){} @Override public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
        btnResetPreamp.setOnClickListener(v->{ seekPreamp.setProgress(120); dspProcessor.setPreamp(0f); updateHeadroomDisplay(); saveSettings(); });
        switchGlobalAudioMode.setOnCheckedChangeListener((b,c)->{ if(c)GlobalAudioService.Companion.start(this); else GlobalAudioService.Companion.stop(this); mainHandler.postDelayed(()->checkGlobalBypassStatus(),300); });
        seekPreGainBass.setOnSeekBarChangeListener(gainListener(v->dspProcessor.setBassGain(v),tvPreGainBassValue));
        seekPreGainMid.setOnSeekBarChangeListener(gainListener(v->dspProcessor.setMidGain(v),tvPreGainMidValue));
        seekPreGainTreble.setOnSeekBarChangeListener(gainListener(v->dspProcessor.setTrebleGain(v),tvPreGainTrebleValue));
        btnResetPreGains.setOnClickListener(v->{ seekPreGainBass.setProgress(120); seekPreGainMid.setProgress(120); seekPreGainTreble.setProgress(120); dspProcessor.setBassGain(0f); dspProcessor.setMidGain(0f); dspProcessor.setTrebleGain(0f); saveSettings(); });
        btnResetEq.setOnClickListener(v->{ for(int i=0;i<32;i++){ eqSeekBars[i].setProgress(120); dspProcessor.setBandLevel(i,0f); if(audioSessionManager!=null)audioSessionManager.setBandGain(i,0f);} saveSettings(); Toast.makeText(this,"EQ plano",Toast.LENGTH_SHORT).show(); });
        switchBassBoost.setOnCheckedChangeListener((b,c)->{ dspProcessor.setBassBoost(c,dspProcessor.getBassBoostFreq(),dspProcessor.getBassBoostGain()); updateHeadroomDisplay(); saveSettings(); });
        float[] bf={60f,85f,120f}; spinnerBassFreq.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"60 Hz (Sub)","85 Hz (Punch)","120 Hz (Mid-Bass)"})); spinnerBassFreq.setSelection(1);
        spinnerBassFreq.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){ @Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){ dspProcessor.setBassBoost(switchBassBoost.isChecked(),bf[pos],dspProcessor.getBassBoostGain()); saveSettings();} @Override public void onNothingSelected(AdapterView<?>p){} });
        seekBassGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ @Override public void onProgressChanged(SeekBar s,int p,boolean f){ float g=p/10f; tvBassGainValue.setText(String.format(Locale.US,"+%.1f dB",g)); dspProcessor.setBassBoost(switchBassBoost.isChecked(),dspProcessor.getBassBoostFreq(),g); updateHeadroomDisplay();} @Override public void onStartTrackingTouch(SeekBar s){} @Override public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
        switchHardwareEmulation.setOnCheckedChangeListener((b,c)->{ dspProcessor.setEmulationEnabled(c); updateHeadroomDisplay(); saveSettings(); updateBtStatus(); });
        seekEmulationAmount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ @Override public void onProgressChanged(SeekBar s,int p,boolean f){ tvEmulationAmount.setText(p+"%"); dspProcessor.setEmulationAmount(p/100f); updateHeadroomDisplay();} @Override public void onStartTrackingTouch(SeekBar s){} @Override public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
        switchBluetoothBypass.setOnCheckedChangeListener((b,c)->{ dspProcessor.setBluetoothAutoBypass(c); updateHeadroomDisplay(); updateBtStatus(); saveSettings(); });
        switchMdrc.setOnCheckedChangeListener((b,c)->{ dspProcessor.setMdrcEnabled(c); saveSettings(); });
        seekMdrcThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ @Override public void onProgressChanged(SeekBar s,int p,boolean f){ float t=-36f+p; tvMdrcThresholdValue.setText(String.format(Locale.US,"Umbral: %.0f dB",t)); dspProcessor.setMdrcDynamics(t,dspProcessor.getMdrcRatio());} @Override public void onStartTrackingTouch(SeekBar s){} @Override public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
        seekMdrcRatio.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ @Override public void onProgressChanged(SeekBar s,int p,boolean f){ float r=1f+p/10f; tvMdrcRatioValue.setText(String.format(Locale.US,"Ratio: %.1f:1",r)); dspProcessor.setMdrcDynamics(dspProcessor.getMdrcThreshold(),r);} @Override public void onStartTrackingTouch(SeekBar s){} @Override public void onStopTrackingTouch(SeekBar s){ saveSettings(); }});
        setupMdrc(seekMdrcSub,tvMdrcSub,0); setupMdrc(seekMdrcLow,tvMdrcLow,1); setupMdrc(seekMdrcMid,tvMdrcMid,2); setupMdrc(seekMdrcHigh,tvMdrcHigh,3); setupMdrc(seekMdrcAir,tvMdrcAir,4);
        setupPresetButtons(); updateBtStatus();
    }
    interface GA{ void a(float g); }
    private SeekBar.OnSeekBarChangeListener gainListener(GA a,TextView tv){ return new SeekBar.OnSeekBarChangeListener(){ @Override public void onProgressChanged(SeekBar s,int p,boolean f){ float g=(p-120)/10f; tv.setText(String.format(Locale.US,"%+.1f dB",g)); a.a(g);} @Override public void onStartTrackingTouch(SeekBar s){} @Override public void onStopTrackingTouch(SeekBar s){ saveSettings(); }}; }
    private void setupMdrc(SeekBar sb,TextView tv,int b){ sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ @Override public void onProgressChanged(SeekBar s,int p,boolean f){ float g=(p-120)/10f; tv.setText(String.format(Locale.US,"%+.1f dB",g)); dspProcessor.setMdrcBandGain(b,g);} @Override public void onStartTrackingTouch(SeekBar s){} @Override public void onStopTrackingTouch(SeekBar s){ saveSettings(); }}); }

    private void setupPresetButtons(){
        btnPresetFlat.setOnClickListener(v->{ applyPresetCurve(new float[32]); Toast.makeText(this,"Flat",Toast.LENGTH_SHORT).show(); });
        btnPresetBass.setOnClickListener(v->{ float[] c=new float[32]; for(int i=0;i<=10;i++)c[i]=5f-i*0.45f; applyPresetCurve(c); seekBassGain.setProgress(80); Toast.makeText(this,"Bass",Toast.LENGTH_SHORT).show(); });
        btnPresetRock.setOnClickListener(v->{ float[] c=new float[32]; c[0]=4f;c[1]=4f;c[2]=3.5f;c[3]=3f;c[16]=-1f;c[17]=-1.5f;c[18]=-1f;c[26]=2.5f;c[27]=3f;c[28]=3.5f;c[29]=4f; applyPresetCurve(c); });
        btnPresetVocal.setOnClickListener(v->{ float[] c=new float[32]; c[0]=-3f;c[1]=-3f;c[2]=-2.5f;c[16]=2f;c[17]=3f;c[18]=3.5f;c[19]=3f;c[20]=2.5f; applyPresetCurve(c); });
        List<String> names=new ArrayList<>(Arrays.asList("Studio Master (Flat)","Bass Heavy Sub","Rock & Roll V-Shape","Acoustic / Vocal","Electronic & EDM"));
        // carga customs guardados
        for(String k: prefs.getAll().keySet()) if(k.startsWith("custom_preset_")){ String n=k.replace("custom_preset_","").replace("_bands",""); if(!names.contains(n)) names.add(n); }
        ArrayAdapter<String> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names); spinnerPresets.setAdapter(ad);
        spinnerPresets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){ @Override public void onItemSelected(AdapterView<?>p,View v,int pos,long id){ String n=names.get(pos); if(n.startsWith("Studio Master")) btnPresetFlat.performClick(); else if(n.contains("Bass Heavy")) btnPresetBass.performClick(); else if(n.contains("Rock")) btnPresetRock.performClick(); else if(n.contains("Acoustic")) btnPresetVocal.performClick(); else if(n.contains("Electronic")){ float[] e=new float[32]; e[0]=5f;e[1]=5f;e[2]=4.5f;e[28]=2.5f;e[29]=3f;e[30]=3.5f;e[31]=4f; applyPresetCurve(e);} else loadCustomPreset(n); } @Override public void onNothingSelected(AdapterView<?>p){} });
        btnSaveCustomPreset.setOnClickListener(v->showSavePresetDialog(names,ad));
        btnApplyGenreEq.setOnClickListener(v->{
            String[] g=dspProcessor.detectGenre();
            tvDetectedGenre.setText(g[0]); tvDetectedGenreConfidence.setText("Confianza: "+g[1]+"%");
            if(g[0].contains("Bass")||g[0].contains("EDM")||g[0].contains("Hip")) btnPresetBass.performClick();
            else if(g[0].contains("Vocal")||g[0].contains("Acústico")) btnPresetVocal.performClick();
            else if(g[0].contains("Pop")||g[0].contains("Electronic")){ float[] e=new float[32]; e[0]=4f;e[31]=4f; applyPresetCurve(e); }
            else btnPresetRock.performClick();
            Toast.makeText(this,"Aplicado: "+g[0],Toast.LENGTH_SHORT).show();
        });
    }

    private void applyPresetCurve(float[] c){ for(int i=0;i<Math.min(c.length,32);i++){ int pr=Math.round(c[i]*10f+120f); if(eqSeekBars[i]!=null)eqSeekBars[i].setProgress(pr); dspProcessor.setBandLevel(i,c[i]); if(audioSessionManager!=null)audioSessionManager.setBandGain(i,c[i]); } saveSettings(); }
    private void loadCustomPreset(String name){
        String key="custom_preset_"+name+"_bands";
        String data=prefs.getString(key,null); if(data==null) return;
        String[] parts=data.split(","); float[] c=new float[32];
        for(int i=0;i<Math.min(32,parts.length);i++){ try{ c[i]=Float.parseFloat(parts[i]); }catch(Exception e){ c[i]=0; } }
        applyPresetCurve(c); Toast.makeText(this,"Preset '"+name+"' cargado",Toast.LENGTH_SHORT).show();
    }
    private void showSavePresetDialog(List<String> names,ArrayAdapter<String> ad){
        AlertDialog.Builder b=new AlertDialog.Builder(this); b.setTitle("Guardar Preset");
        EditText in=new EditText(this); in.setHint("Nombre"); b.setView(in);
        b.setPositiveButton("Guardar",(d,w)->{ String n=in.getText().toString().trim(); if(n.isEmpty())return;
            StringBuilder sb=new StringBuilder(); for(int i=0;i<32;i++){ if(i>0)sb.append(","); sb.append(dspProcessor.getBandGain(i)); }
            prefs.edit().putString("custom_preset_"+n+"_bands",sb.toString()).apply();
            if(!names.contains(n)){ names.add(n); ad.notifyDataSetChanged(); } spinnerPresets.setSelection(names.indexOf(n));
            Toast.makeText(this,"Preset '"+n+"' guardado",Toast.LENGTH_SHORT).show(); });
        b.setNegativeButton("Cancelar",null); b.show();
    }

    private void updateHeadroomDisplay(){ if(dspProcessor!=null&&tvEffectivePreamp!=null) tvEffectivePreamp.setText(String.format(Locale.US,"Auto-Headroom: %+.1f dB",dspProcessor.getEffectivePreampDb())); }
    private void updateBtStatus(){ if(tvBluetoothStatus==null)return; boolean bypass=switchBluetoothBypass!=null&&switchBluetoothBypass.isChecked(); boolean emuOn=dspProcessor!=null&&dspProcessor.isEmulationEnabled(); if(!bypass) tvBluetoothStatus.setText("BT Bypass: OFF"); else tvBluetoothStatus.setText(emuOn?"BT Bypass: ON (EMU se bypasea en BT)":"BT Bypass: ON"); }
    private void checkGlobalBypassStatus(){
        boolean running=false; try{ running=GlobalAudioService.Companion.isServiceRunning()||GlobalAudioService.isGlobalAudioEnabled()||(audioSessionManager!=null&&audioSessionManager.isGlobalAudioEnabled()); }catch(Exception e){}
        dspProcessor.setGlobalBypass(false);
        if(bannerGlobalBypass!=null)bannerGlobalBypass.setVisibility(running?View.VISIBLE:View.GONE);
        if(switchGlobalAudioMode!=null)switchGlobalAudioMode.setChecked(running);
        if(tvCaptureSessionCount!=null&&audioSessionManager!=null)tvCaptureSessionCount.setText("Sesión: "+audioSessionManager.getActiveSessionsCount()+" activas");
        updateBtStatus();
    }
    private void loadSavedSettings(){
        boolean m=prefs.getBoolean(KEY_MASTER_ENABLED,true); switchMasterDsp.setChecked(m); switchMasterDspSection.setChecked(m); dspProcessor.setMasterEnabled(m);
        float pre=prefs.getFloat(KEY_PREAMP,0f); seekPreamp.setProgress(Math.round(pre*10f+120f)); dspProcessor.setPreamp(pre);
        float pb=prefs.getFloat(KEY_PREGAIN_BASS,0f); seekPreGainBass.setProgress(Math.round(pb*10f+120f)); dspProcessor.setBassGain(pb);
        float pm=prefs.getFloat(KEY_PREGAIN_MID,0f); seekPreGainMid.setProgress(Math.round(pm*10f+120f)); dspProcessor.setMidGain(pm);
        float pt=prefs.getFloat(KEY_PREGAIN_TREBLE,0f); seekPreGainTreble.setProgress(Math.round(pt*10f+120f)); dspProcessor.setTrebleGain(pt);
        for(int i=0;i<32;i++){ float g=prefs.getFloat("eq_band_"+i,0f); if(eqSeekBars[i]!=null)eqSeekBars[i].setProgress(Math.round(g*10f+120f)); dspProcessor.setBandLevel(i,g); }
        boolean bb=prefs.getBoolean(KEY_BASSBOOST_ENABLED,true); float bg=prefs.getFloat(KEY_BASSBOOST_GAIN,4f); switchBassBoost.setChecked(bb); seekBassGain.setProgress(Math.round(bg*10f)); dspProcessor.setBassBoost(bb,85f,bg);
        boolean emu=prefs.getBoolean(KEY_EMU_ENABLED,true); float ea=prefs.getFloat(KEY_EMU_AMOUNT,0.8f); switchHardwareEmulation.setChecked(emu); seekEmulationAmount.setProgress(Math.round(ea*100f)); dspProcessor.setEmulationEnabled(emu); dspProcessor.setEmulationAmount(ea);
        boolean mdrc=prefs.getBoolean(KEY_MDRC_ENABLED,true); switchMdrc.setChecked(mdrc); dspProcessor.setMdrcEnabled(mdrc);
    }
    private void saveSettings(){
        if(prefs==null||dspProcessor==null)return; SharedPreferences.Editor e=prefs.edit();
        e.putBoolean(KEY_MASTER_ENABLED,dspProcessor.isMasterEnabled()); e.putFloat(KEY_PREAMP,dspProcessor.getPreamp());
        e.putFloat(KEY_PREGAIN_BASS,dspProcessor.getBassGain()); e.putFloat(KEY_PREGAIN_MID,dspProcessor.getMidGain()); e.putFloat(KEY_PREGAIN_TREBLE,dspProcessor.getTrebleGain());
        for(int i=0;i<32;i++)e.putFloat("eq_band_"+i,dspProcessor.getBandGain(i));
        e.putBoolean(KEY_BASSBOOST_ENABLED,dspProcessor.isBassBoostEnabled()); e.putFloat(KEY_BASSBOOST_GAIN,dspProcessor.getBassBoostGain());
        e.putBoolean(KEY_EMU_ENABLED,dspProcessor.isEmulationEnabled()); e.putFloat(KEY_EMU_AMOUNT,dspProcessor.getEmulationAmount());
        e.putBoolean(KEY_MDRC_ENABLED,dspProcessor.isMdrcEnabled()); e.apply();
    }
    private int dpToPx(int dp){ return Math.round(dp*getResources().getDisplayMetrics().density); }
}
