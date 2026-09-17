package com.sjbz.aimp.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SjbzDspProcessor {

    public static final int BAND_COUNT = 32;

    public static final float[] BAND_FREQS = {
        20.0f, 25.0f, 31.0f, 40.0f, 50.0f, 63.0f, 80.0f, 100.0f,
        125.0f, 160.0f, 200.0f, 250.0f, 315.0f, 400.0f, 500.0f, 630.0f,
        800.0f, 1000.0f, 1250.0f, 1600.0f, 2000.0f, 2500.0f, 3150.0f, 4000.0f,
        5000.0f, 6300.0f, 8000.0f, 10000.0f, 12500.0f, 14000.0f, 16000.0f, 20000.0f
    };

    public static final float[] ISO_FREQUENCIES = BAND_FREQS;

    public static final String[] BAND_LABELS = {
        "20", "25", "31", "40", "50", "63", "80", "100",
        "125", "160", "200", "250", "315", "400", "500", "630",
        "800", "1k", "1.2k", "1.6k", "2k", "2.5k", "3.1k", "4k",
        "5k", "6.3k", "8k", "10k", "12.5k", "14k", "16k", "20k"
    };

    public interface FftListener { void onFftData(float[] samples); }
    public interface ClippingListener { void onClipping(boolean isClipping); }

    private static class Biquad {
        float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
        float a1 = 0.0f, a2 = 0.0f;
        float x1_0 = 0.0f, x2_0 = 0.0f, y1_0 = 0.0f, y2_0 = 0.0f;
        float x1_1 = 0.0f, x2_1 = 0.0f, y1_1 = 0.0f, y2_1 = 0.0f;
        void reset() {
            x1_0=0; x2_0=0; y1_0=0; y2_0=0;
            x1_1=0; x2_1=0; y1_1=0; y2_1=0;
        }
        void checkSanity() {
            if (Float.isNaN(y1_0) || Float.isInfinite(y1_0) || Float.isNaN(y2_0) || Float.isInfinite(y2_0)) reset();
            if (Float.isNaN(y1_1) || Float.isInfinite(y1_1) || Float.isNaN(y2_1) || Float.isInfinite(y2_1)) reset();
        }
        float processChannel(float in, int ch) {
            float out;
            if (ch == 0) {
                out = b0*in + b1*x1_0 + b2*x2_0 - a1*y1_0 - a2*y2_0;
                x2_0=x1_0; x1_0=in; y2_0=y1_0; y1_0=out;
            } else {
                out = b0*in + b1*x1_1 + b2*x2_1 - a1*y1_1 - a2*y2_1;
                x2_1=x1_1; x1_1=in; y2_1=y1_1; y1_1=out;
            }
            return out;
        }
        void setPeaking(float f0, float Fs, float gainDb, float Q) {
            if (Math.abs(gainDb) < 0.001f) { setBypass(); return; }
            f0 = Math.max(10.0f, Math.min(f0, Fs*0.49f));
            double A = Math.pow(10.0, gainDb/40.0);
            double w0 = 2.0*Math.PI*f0/Fs;
            double alpha = Math.sin(w0)/(2.0*Math.max(0.1,(double)Q));
            double cosw0 = Math.cos(w0);
            double a0 = 1.0 + alpha/A;
            b0=(float)((1.0+alpha*A)/a0); b1=(float)((-2.0*cosw0)/a0); b2=(float)((1.0-alpha*A)/a0);
            a1=(float)((-2.0*cosw0)/a0); a2=(float)((1.0-alpha/A)/a0);
        }
        void setLowShelf(float f0, float Fs, float gainDb, float Q) {
            if (Math.abs(gainDb) < 0.001f) { setBypass(); return; }
            f0 = Math.max(10.0f, Math.min(f0, Fs*0.49f));
            double A=Math.pow(10.0,gainDb/40.0); double w0=2.0*Math.PI*f0/Fs;
            double cosw0=Math.cos(w0); double sinw0=Math.sin(w0);
            double alpha=sinw0/(2.0*Math.max(0.1,(double)Q));
            double t=2.0*Math.sqrt(A)*alpha;
            double a0=(A+1.0)+(A-1.0)*cosw0+t;
            b0=(float)((A*((A+1.0)-(A-1.0)*cosw0+t))/a0);
            b1=(float)((2.0*A*((A-1.0)-(A+1.0)*cosw0))/a0);
            b2=(float)((A*((A+1.0)-(A-1.0)*cosw0-t))/a0);
            a1=(float)((-2.0*((A-1.0)+(A+1.0)*cosw0))/a0);
            a2=(float)(((A+1.0)+(A-1.0)*cosw0-t)/a0);
        }
        void setHighShelf(float f0, float Fs, float gainDb, float Q) {
            if (Math.abs(gainDb) < 0.001f) { setBypass(); return; }
            f0 = Math.max(10.0f, Math.min(f0, Fs*0.49f));
            double A=Math.pow(10.0,gainDb/40.0); double w0=2.0*Math.PI*f0/Fs;
            double cosw0=Math.cos(w0); double sinw0=Math.sin(w0);
            double alpha=sinw0/(2.0*Math.max(0.1,(double)Q));
            double t=2.0*Math.sqrt(A)*alpha;
            double a0=(A+1.0)-(A-1.0)*cosw0+t;
            b0=(float)((A*((A+1.0)+(A-1.0)*cosw0+t))/a0);
            b1=(float)((-2.0*A*((A-1.0)+(A+1.0)*cosw0))/a0);
            b2=(float)((A*((A+1.0)+(A-1.0)*cosw0-t))/a0);
            a1=(float)((2.0*((A-1.0)-(A+1.0)*cosw0))/a0);
            a2=(float)(((A+1.0)-(A-1.0)*cosw0-t)/a0);
        }
        void setBypass(){ b0=1.0f; b1=0.0f; b2=0.0f; a1=0.0f; a2=0.0f; }
    }

    private float sampleRate = 48000.0f;
    private volatile boolean masterEnabled = true;
    private volatile boolean globalBypass = false;
    private volatile boolean limiterEnabled = true; // PARCHE 1

    private float preGainBassDb=0, preGainMidDb=0, preGainTrebleDb=0;
    private final Biquad preGainBassFilter=new Biquad();
    private final Biquad preGainMidFilter=new Biquad();
    private final Biquad preGainTrebleFilter=new Biquad();
    private final float[] bandGainsDb=new float[BAND_COUNT];
    private final Biquad[] eqBiquads=new Biquad[BAND_COUNT];
    private volatile boolean bassBoostEnabled=true;
    private float bassBoostFreq=85.0f, bassBoostGainDb=4.0f;
    private final Biquad bassBoostBiquad=new Biquad();
    private volatile boolean emulationEnabled=true;
    private float emulationAmount=0.8f;
    private volatile boolean bluetoothAutoBypass=false;
    private final Biquad emuLowBiquad=new Biquad();
    private final Biquad emuMidDipBiquad=new Biquad();
    private final Biquad emuPresenceBiquad=new Biquad();
    private final Biquad emuAirBiquad=new Biquad();
    private volatile boolean mdrcEnabled=true;
    private float mdrcThresholdDb=-18.0f, mdrcRatio=2.5f;
    private final float[] mdrcBandGainsDb=new float[5];
    private final Biquad[] mdrcCrossoverBiquads=new Biquad[4];
    private float mdrcGainReduction=0, mdrcEnvelope=0;
    private float preampDb=0, effectivePreampDb=0, linearEffectivePreamp=1.0f;
    private float limiterEnvelope=0;
    private static final float LIMITER_THRESHOLD=0.89125f;
    private final float limiterAttackCoeff, limiterReleaseCoeff;
    private FftListener fftListener; private ClippingListener clippingListener;
    private final float[] visualizerBuffer=new float[256]; private int visualizerIndex=0;

    public SjbzDspProcessor(){ this(48000.0f); }
    public SjbzDspProcessor(float sampleRate){
        this.sampleRate=(sampleRate>8000.0f)?sampleRate:48000.0f;
        this.limiterAttackCoeff=(float)Math.exp(-1.0/(0.002*this.sampleRate));
        this.limiterReleaseCoeff=(float)Math.exp(-1.0/(0.100*this.sampleRate));
        for(int i=0;i<BAND_COUNT;i++){ eqBiquads[i]=new Biquad(); bandGainsDb[i]=0; updateEqBandFilter(i); }
        updatePreGainFilters(); updateBassBoostFilter(); updateEmulationFilters();
        for(int i=0;i<4;i++) mdrcCrossoverBiquads[i]=new Biquad();
        updateMdrcCrossovers(); updateAutoHeadroom();
    }

    // PARCHE 1: metodos faltantes
    public void setLimiterEnabled(boolean e){ this.limiterEnabled=e; }
    public void setLimiterThreshold(float db){}
    public boolean isLimiterEnabled(){ return limiterEnabled; }

    public synchronized void setSampleRate(float sr){
        if(sr>8000.0f && Math.abs(this.sampleRate-sr)>1.0f){
            this.sampleRate=sr;
            for(int i=0;i<BAND_COUNT;i++) updateEqBandFilter(i);
            updatePreGainFilters(); updateBassBoostFilter(); updateEmulationFilters(); updateMdrcCrossovers(); resetFilterStates();
        }
    }
    public void setFftListener(FftListener l){ this.fftListener=l; }
    public void setClippingListener(ClippingListener l){ this.clippingListener=l; }
    public boolean isMasterEnabled(){ return masterEnabled; }
    public void setMasterEnabled(boolean e){ this.masterEnabled=e; }
    public boolean isGlobalBypass(){ return globalBypass; }
    public void setGlobalBypass(boolean b){ this.globalBypass=b; }
    public float getPreamp(){ return preampDb; }
    public synchronized void setPreamp(float db){ this.preampDb=Math.max(-12.0f,Math.min(12.0f,db)); updateAutoHeadroom(); }
    public float getEffectivePreampDb(){ return effectivePreampDb; }
    private void updateAutoHeadroom(){
        float bb=bassBoostEnabled?(bassBoostGainDb*0.7f):0;
        float em=(emulationEnabled &&!bluetoothAutoBypass)?(emulationAmount*4.0f):0;
        this.effectivePreampDb=this.preampDb-bb-em;
        this.linearEffectivePreamp=(float)Math.pow(10.0,effectivePreampDb/20.0);
    }
    public float getBassGain(){ return preGainBassDb; }
    public synchronized void setBassGain(float db){ this.preGainBassDb=Math.max(-12.0f,Math.min(12.0f,db)); preGainBassFilter.setLowShelf(200.0f,sampleRate,preGainBassDb,0.707f); }
    public float getMidGain(){ return preGainMidDb; }
    public synchronized void setMidGain(float db){ this.preGainMidDb=Math.max(-12.0f,Math.min(12.0f,db)); preGainMidFilter.setPeaking(1000.0f,sampleRate,preGainMidDb,1.0f); }
    public float getTrebleGain(){ return preGainTrebleDb; }
    public synchronized void setTrebleGain(float db){ this.preGainTrebleDb=Math.max(-12.0f,Math.min(12.0f,db)); preGainTrebleFilter.setHighShelf(6000.0f,sampleRate,preGainTrebleDb,0.707f); }
    private void updatePreGainFilters(){ setBassGain(preGainBassDb); setMidGain(preGainMidDb); setTrebleGain(preGainTrebleDb); }
    public float getBandGain(int i){ return (i>=0&&i<BAND_COUNT)?bandGainsDb[i]:0; }
    public synchronized void setBandLevel(int i,float g){ if(i>=0&&i<BAND_COUNT){ bandGainsDb[i]=Math.max(-12.0f,Math.min(12.0f,g)); updateEqBandFilter(i);} }
    private void updateEqBandFilter(int i){ eqBiquads[i].setPeaking(BAND_FREQS[i],sampleRate,bandGainsDb[i],1.414f); }
    public boolean isBassBoostEnabled(){ return bassBoostEnabled; }
    public float getBassBoostFreq(){ return bassBoostFreq; }
    public float getBassBoostGain(){ return bassBoostGainDb; }
    public synchronized void setBassBoost(boolean e,float f,float g){ this.bassBoostEnabled=e; this.bassBoostFreq=f; this.bassBoostGainDb=Math.max(0,Math.min(12,g)); updateBassBoostFilter(); updateAutoHeadroom(); }
    private void updateBassBoostFilter(){ if(bassBoostEnabled&&bassBoostGainDb>0.05f) bassBoostBiquad.setPeaking(bassBoostFreq,sampleRate,bassBoostGainDb,1.1f); else bassBoostBiquad.setBypass(); }
    public boolean isEmulationEnabled(){ return emulationEnabled; }
    public float getEmulationAmount(){ return emulationAmount; }
    public boolean isBluetoothAutoBypass(){ return bluetoothAutoBypass; }
    public synchronized void setEmulationEnabled(boolean e){ this.emulationEnabled=e; updateEmulationFilters(); updateAutoHeadroom(); }
    public synchronized void setEmulationAmount(float a){ this.emulationAmount=Math.max(0,Math.min(1,a)); updateEmulationFilters(); updateAutoHeadroom(); }
    public synchronized void setBluetoothAutoBypass(boolean b){ this.bluetoothAutoBypass=b; updateAutoHeadroom(); }
    private void updateEmulationFilters(){
        if(emulationEnabled&&!bluetoothAutoBypass){
            float amt=emulationAmount;
            emuLowBiquad.setLowShelf(110.0f,sampleRate,3.5f*amt,0.8f);
            emuMidDipBiquad.setPeaking(650.0f,sampleRate,-1.8f*amt,1.5f);
            emuPresenceBiquad.setPeaking(3800.0f,sampleRate,2.2f*amt,1.2f);
            emuAirBiquad.setHighShelf(11500.0f,sampleRate,1.8f*amt,0.7f);
        } else { emuLowBiquad.setBypass(); emuMidDipBiquad.setBypass(); emuPresenceBiquad.setBypass(); emuAirBiquad.setBypass(); }
    }
    public boolean isMdrcEnabled(){ return mdrcEnabled; }
    public float getMdrcThreshold(){ return mdrcThresholdDb; }
    public float getMdrcRatio(){ return mdrcRatio; }
    public float getMdrcGainReduction(){ return mdrcGainReduction; }
    public synchronized void setMdrcEnabled(boolean e){ this.mdrcEnabled=e; }
    public synchronized void setMdrcDynamics(float t,float r){ this.mdrcThresholdDb=t; this.mdrcRatio=Math.max(1.0f,r); }
    public synchronized void setMdrcBandGain(int i,float g){ if(i>=0&&i<5) mdrcBandGainsDb[i]=g; }
    private void updateMdrcCrossovers(){
        mdrcCrossoverBiquads[0].setLowShelf(160.0f,sampleRate,0.0f,0.707f);
        mdrcCrossoverBiquads[1].setPeaking(500.0f,sampleRate,0.0f,1.0f);
        mdrcCrossoverBiquads[2].setPeaking(2000.0f,sampleRate,0.0f,1.0f);
        mdrcCrossoverBiquads[3].setHighShelf(7000.0f,sampleRate,0.0f,0.707f);
    }
    public void resetFilterStates(){
        preGainBassFilter.reset(); preGainMidFilter.reset(); preGainTrebleFilter.reset();
        for(int i=0;i<BAND_COUNT;i++) eqBiquads[i].reset();
        bassBoostBiquad.reset(); emuLowBiquad.reset(); emuMidDipBiquad.reset(); emuPresenceBiquad.reset(); emuAirBiquad.reset();
        for(int i=0;i<4;i++) mdrcCrossoverBiquads[i].reset();
        limiterEnvelope=0; mdrcEnvelope=0;
    }

    public void process(float[] buffer,int channels,int frameCount){
        if(!masterEnabled||globalBypass||buffer==null||frameCount<=0) return;
        boolean clipping=false;
        final float linPreamp=this.linearEffectivePreamp;
        final boolean runEmu=(emulationEnabled&&!bluetoothAutoBypass);
        final boolean runMdrc=mdrcEnabled;
        final float thLin=(float)Math.pow(10.0,mdrcThresholdDb/20.0);
        int total=frameCount*channels;
        for(int i=0;i<total;i+=channels){
            for(int ch=0;ch<channels;ch++){
                int idx=i+ch; float s=buffer[idx];
                if(Float.isNaN(s)||Float.isInfinite(s)) s=0;
                s=s*linPreamp;
                s=preGainBassFilter.processChannel(s,ch);
                s=preGainMidFilter.processChannel(s,ch);
                s=preGainTrebleFilter.processChannel(s,ch);
                for(int b=0;b<BAND_COUNT;b++) s=eqBiquads[b].processChannel(s,ch);
                if(bassBoostEnabled) s=bassBoostBiquad.processChannel(s,ch);
                if(runEmu){
                    s=emuLowBiquad.processChannel(s,ch); s=emuMidDipBiquad.processChannel(s,ch);
                    s=emuPresenceBiquad.processChannel(s,ch); s=emuAirBiquad.processChannel(s,ch);
                    if(s>0.5f) s=0.5f+(float)Math.tanh(s-0.5f)*0.5f;
                    else if(s<-0.5f) s=-0.5f+(float)Math.tanh(s+0.5f)*0.5f;
                }
                // PARCHE 4: trim MDRC
                float trim=(mdrcBandGainsDb[0]+mdrcBandGainsDb[1]+mdrcBandGainsDb[2]+mdrcBandGainsDb[3]+mdrcBandGainsDb[4])/5.0f;
                if(Math.abs(trim)>0.01f) s*=(float)Math.pow(10.0,trim/40.0);
                if(runMdrc){
                    float a=Math.abs(s);
                    if(a>mdrcEnvelope) mdrcEnvelope=a; else mdrcEnvelope=mdrcEnvelope*0.999f+a*0.001f;
                    if(mdrcEnvelope>thLin){
                        float over=20.0f*(float)Math.log10(mdrcEnvelope/thLin);
                        float red=over*(1.0f-1.0f/mdrcRatio);
                        mdrcGainReduction=red;
                        s*=(float)Math.pow(10.0,-red/20.0);
                    } else mdrcGainReduction=0;
                }
                float as=Math.abs(s);
                if(as>limiterEnvelope) limiterEnvelope=as+limiterAttackCoeff*(limiterEnvelope-as);
                else limiterEnvelope=as+limiterReleaseCoeff*(limiterEnvelope-as);
                // PARCHE 2: limiterEnabled
                if(limiterEnabled && limiterEnvelope>LIMITER_THRESHOLD){ s*=LIMITER_THRESHOLD/limiterEnvelope; clipping=true; }
                if(s>1.0f){ s=1.0f; clipping=true; } else if(s<-1.0f){ s=-1.0f; clipping=true; }
                if(Float.isNaN(s)||Float.isInfinite(s)){ s=0; resetFilterStates(); }
                buffer[idx]=s;
                // PARCHE 3: sanity cada 512
                if((i&511)==0){ for(int b=0;b<BAND_COUNT;b++) eqBiquads[b].checkSanity(); }
                if(ch==0&&fftListener!=null){ visualizerBuffer[visualizerIndex++]=s; if(visualizerIndex>=visualizerBuffer.length){ visualizerIndex=0; fftListener.onFftData(visualizerBuffer.clone()); } }
            }
        }
        if(clippingListener!=null&&clipping) clippingListener.onClipping(true);
    }

    public void processPcm16(byte[] pcm,int off,int len,int ch){
        if(!masterEnabled||globalBypass||pcm==null||len<=0) return;
        int sc=len/2; int fc=sc/ch; float[] fb=new float[sc];
        for(int i=0;i<sc;i++){ int bi=off+i*2; short v=(short)((pcm[bi]&0xFF)|(pcm[bi+1]<<8)); fb[i]=v/32768.0f; }
        process(fb,ch,fc);
        for(int i=0;i<sc;i++){ int bi=off+i*2; float f=Math.max(-1.0f,Math.min(1.0f,fb[i])); short sv=(short)Math.round(f*32767.0f); pcm[bi]=(byte)(sv&0xFF); pcm[bi+1]=(byte)((sv>>8)&0xFF); }
    }
    public void processByteBuffer(ByteBuffer bb,int ch){
        if(!masterEnabled||globalBypass||bb==null||!bb.hasRemaining()) return;
        int rem=bb.remaining(); byte[] t=new byte[rem]; int pos=bb.position(); bb.get(t);
        processPcm16(t,0,rem,ch); bb.position(pos); bb.put(t);
    }
}
