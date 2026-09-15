package com.sjbz.aimp.audio
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
class SjbzDspProcessor : BaseAudioProcessor() {
    companion object {
        const val BAND_COUNT=32; const val TOTAL_FILTERS=33; const val MAX_CHANNELS=8; const val FFT_BLOCK_SIZE=2048; const val DEFAULT_Q=1.4f; const val EMU_FILTERS=4
        val ISO_FREQUENCIES=floatArrayOf(20f,25f,31.5f,40f,50f,63f,80f,100f,125f,160f,200f,250f,315f,400f,500f,630f,800f,1000f,1250f,1600f,2000f,2500f,3150f,4000f,5000f,6300f,8000f,10000f,12500f,16000f,18000f,20000f)
        val BAND_LABELS=arrayOf("20","25","31","40","50","63","80","100","125","160","200","250","315","400","500","630","800","1k","1.25k","1.6k","2k","2.5k","3.15k","4k","5k","6.3k","8k","10k","12.5k","16k","18k","20k")
    }
    var masterEnabled:Boolean=true
    private var preampDb=0f; private var linearPreamp=1f
    private var bassEnabled=true; private var bassFreqHz=85f; private var bassGainDb=0f
    private val bandGainsDb=FloatArray(BAND_COUNT)
    private var emulationEnabled=false; private var emulationAmount=0.8f; private var bluetoothAutoBypass=true
    @Volatile private var bluetoothConnected=false
    private val emuB0=FloatArray(EMU_FILTERS); private val emuB1=FloatArray(EMU_FILTERS); private val emuB2=FloatArray(EMU_FILTERS); private val emuA1=FloatArray(EMU_FILTERS); private val emuA2=FloatArray(EMU_FILTERS)
    private val emuS1=FloatArray(MAX_CHANNELS*EMU_FILTERS); private val emuS2=FloatArray(MAX_CHANNELS*EMU_FILTERS)
    private val limiterThresholdLin=10.0.pow(-6.0/20.0).toFloat()
    private var limiterAlphaAtt=0f; private var limiterAlphaRel=0f; private val emuEnv=FloatArray(MAX_CHANNELS)
    private var masteringScratch=FloatArray(MAX_CHANNELS*1024)
    @Volatile private var isDirty=true; private var currentSampleRate=48000f
    private val b0Array=FloatArray(TOTAL_FILTERS); private val b1Array=FloatArray(TOTAL_FILTERS); private val b2Array=FloatArray(TOTAL_FILTERS); private val a1Array=FloatArray(TOTAL_FILTERS); private val a2Array=FloatArray(TOTAL_FILTERS)
    private val s1=FloatArray(MAX_CHANNELS*TOTAL_FILTERS); private val s2=FloatArray(MAX_CHANNELS*TOTAL_FILTERS)
    private val fftRingBuffer=FloatArray(FFT_BLOCK_SIZE); private val fftDispatchBuffer=FloatArray(FFT_BLOCK_SIZE); private var fftRingIndex=0
    var fftListener:((FloatArray)->Unit)?=null
    private val autoGain=AutoGain(); private var truePeakLimiter=TruePeakLimiter(48000)
    init{recalculateCoefficients()}
    override fun onConfigure(inputAudioFormat:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat{
        if(inputAudioFormat.encoding!=C.ENCODING_PCM_FLOAT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        currentSampleRate=inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f)
        truePeakLimiter.setSampleRate(inputAudioFormat.sampleRate); isDirty=true; return inputAudioFormat
    }
    override fun onReset(){s1.fill(0f);s2.fill(0f);emuS1.fill(0f);emuS2.fill(0f);emuEnv.fill(0f);fftRingBuffer.fill(0f);fftRingIndex=0;truePeakLimiter.reset();isDirty=true}
    override fun queueInput(inputBuffer:ByteBuffer){
        val remainingBytes=inputBuffer.remaining(); if(remainingBytes==0) return
        if(isDirty) recalculateCoefficients()
        val outputBuffer=replaceOutputBuffer(remainingBytes); outputBuffer.order(ByteOrder.nativeOrder()); inputBuffer.order(ByteOrder.nativeOrder())
        val channelCount=inputAudioFormat.channelCount.coerceIn(1,MAX_CHANNELS); val totalFloats=remainingBytes/4; val frames=totalFloats/channelCount
        if(masteringScratch.size<totalFloats) masteringScratch=FloatArray(totalFloats)
        val localMasterEnabled=masterEnabled; val localLinearPreamp=linearPreamp; val localListener=fftListener
        val isEmuActive=localMasterEnabled&&emulationEnabled&&(!bluetoothAutoBypass||!bluetoothConnected)
        val localEmuAmount=emulationAmount.coerceIn(0f,1f); val localThresh=limiterThresholdLin; val localAtt=limiterAlphaAtt; val localRel=limiterAlphaRel
        var outIdx=0
        for(frame in 0 until frames){
            var monoSum=0f
            for(ch in 0 until channelCount){
                var x=inputBuffer.float*localLinearPreamp
                if(localMasterEnabled){
                    val chOffset=ch*TOTAL_FILTERS
                    for(f in 0 until TOTAL_FILTERS){val sIdx=chOffset+f; val y=b0Array[f]*x+s1[sIdx]; s1[sIdx]=b1Array[f]*x-a1Array[f]*y+s2[sIdx]; s2[sIdx]=b2Array[f]*x-a2Array[f]*y; x=y}
                    if(isEmuActive){
                        val dryX=x; var wetX=x; val eOff=ch*EMU_FILTERS
                        for(ef in 0 until EMU_FILTERS){val sIdx=eOff+ef; val ey=emuB0[ef]*wetX+emuS1[sIdx]; emuS1[sIdx]=emuB1[ef]*wetX-emuA1[ef]*ey+emuS2[sIdx]; emuS2[sIdx]=emuB2[ef]*wetX-emuA2[ef]*ey; wetX=ey}
                        val absX=abs(wetX); var env=emuEnv[ch]
                        env=if(absX>env) localAtt*env+(1f-localAtt)*absX else localRel*env+(1f-localRel)*absX
                        emuEnv[ch]=env; if(env>localThresh) wetX*=(localThresh/env).pow(0.75f)
                        x=(1f-localEmuAmount)*dryX+localEmuAmount*wetX
                    }
                }
                masteringScratch[outIdx++]=x; monoSum+=x
            }
            fftRingBuffer[fftRingIndex++]=monoSum/channelCount
            if(fftRingIndex>=FFT_BLOCK_SIZE){fftRingIndex=0; localListener?.let{System.arraycopy(fftRingBuffer,0,fftDispatchBuffer,0,FFT_BLOCK_SIZE); it(fftDispatchBuffer)}}
        }
        if(localMasterEnabled){
            val compDb=autoGain.compensationDb(preampDb,bandGainsDb,bassGainDb)
            if(compDb!=0f) autoGain.apply(masteringScratch,totalFloats,compDb)
            truePeakLimiter.process(masteringScratch,totalFloats,frames,channelCount)
        }
        outputBuffer.clear(); for(i in 0 until totalFloats) outputBuffer.putFloat(masteringScratch[i]); outputBuffer.flip(); inputBuffer.position(inputBuffer.limit())
    }
    fun setPreamp(g:Float){val c=g.coerceIn(-12f,12f); if(abs(preampDb-c)>0.001f){preampDb=c; linearPreamp=10.0.pow(c/20.0).toFloat()}}
    fun getPreamp():Float=preampDb
    fun setBassBoost(e:Boolean,f:Float,g:Float){val cg=g.coerceIn(0f,12f); val vf=when{f<=70f->60f; f<=100f->85f; else->120f}; if(bassEnabled!=e||abs(bassFreqHz-vf)>0.1f||abs(bassGainDb-cg)>0.01f){bassEnabled=e; bassFreqHz=vf; bassGainDb=cg; isDirty=true}}
    fun isBassBoostEnabled():Boolean=bassEnabled; fun getBassBoostFreq():Float=bassFreqHz; fun getBassBoostGain():Float=bassGainDb
    fun setBandGain(i:Int,g:Float){if(i in 0 until BAND_COUNT){val c=g.coerceIn(-12f,12f); if(abs(bandGainsDb[i]-c)>0.01f){bandGainsDb[i]=c; isDirty=true}}}
    fun getBandGain(i:Int):Float=if(i in 0 until BAND_COUNT) bandGainsDb[i] else 0f
    fun setEmulationEnabled(e:Boolean){if(emulationEnabled!=e){emulationEnabled=e; isDirty=true}}
    fun isEmulationEnabled():Boolean=emulationEnabled; fun setEmulationAmount(a:Float){emulationAmount=a.coerceIn(0f,1f)}; fun getEmulationAmount():Float=emulationAmount
    fun setBluetoothAutoBypass(e:Boolean){bluetoothAutoBypass=e}; fun isBluetoothAutoBypass():Boolean=bluetoothAutoBypass
    fun setBluetoothConnected(c:Boolean){bluetoothConnected=c}; fun isBluetoothConnected():Boolean=bluetoothConnected
    fun isEmulationActive():Boolean=masterEnabled&&emulationEnabled&&(!bluetoothAutoBypass||!bluetoothConnected)
    fun getBandGainsCopy():FloatArray=bandGainsDb.clone()
    private fun recalculateCoefficients(){
        val Fs=currentSampleRate
        if(!bassEnabled||bassGainDb<=0.01f) setFilterBypass(0) else computeLowShelfRbj(0,bassFreqHz,bassGainDb,Fs)
        for(i in 0 until BAND_COUNT){val idx=i+1; val g=bandGainsDb[i]; if(abs(g)<0.01f) setFilterBypass(idx) else computePeakingRbj(idx,ISO_FREQUENCIES[i],g,DEFAULT_Q,Fs)}
        recalculateEmuCoefficients(); isDirty=false
    }
    private fun recalculateEmuCoefficients(){
        val Fs=currentSampleRate
        computeEmuLowShelfRbj(0,80f,2f,Fs); computeEmuPeakingRbj(1,3000f,-1.5f,1.4f,Fs)
        computeEmuHighShelfRbj(2,(Fs*0.45f).coerceAtMost(18000f),-3f,Fs); computeEmuLowPassRbj(3,(Fs*0.45f).coerceAtMost(18500f),0.70710678f,Fs)
        limiterAlphaAtt=exp(-1.0/(Fs*0.005)).toFloat(); limiterAlphaRel=exp(-1.0/(Fs*0.080)).toFloat()
    }
    private fun setFilterBypass(i:Int){b0Array[i]=1f; b1Array[i]=0f; b2Array[i]=0f; a1Array[i]=0f; a2Array[i]=0f}
    private fun computeLowShelfRbj(fi:Int,f0:Float,gDb:Float,sr:Float){val freq=f0.coerceIn(10f,sr*0.49f); val A=10.0.pow(gDb/40.0); val w0=2.0*Math.PI*freq/sr; val c=cos(w0); val s=sin(w0); val al=s*0.7071067811865475; val t=2.0*sqrt(A)*al; val a0=(A+1)+(A-1)*c+t; val i0=1.0/a0; b0Array[fi]=(A*((A+1)-(A-1)*c+t)*i0).toFloat(); b1Array[fi]=(2.0*A*((A-1)-(A+1)*c)*i0).toFloat(); b2Array[fi]=(A*((A+1)-(A-1)*c-t)*i0).toFloat(); a1Array[fi]=(-2.0*((A-1)+(A+1)*c)*i0).toFloat(); a2Array[fi]=(((A+1)+(A-1)*c-t)*i0).toFloat()}
    private fun computePeakingRbj(fi:Int,f0:Float,gDb:Float,q:Float,sr:Float){val freq=f0.coerceIn(10f,sr*0.49f); val A=10.0.pow(gDb/40.0); val w0=2.0*Math.PI*freq/sr; val c=cos(w0); val s=sin(w0); val al=s/(2.0*q); val a0=1.0+al/A; val i0=1.0/a0; b0Array[fi]=((1.0+al*A)*i0).toFloat(); b1Array[fi]=((-2.0*c)*i0).toFloat(); b2Array[fi]=((1.0-al*A)*i0).toFloat(); a1Array[fi]=((-2.0*c)*i0).toFloat(); a2Array[fi]=((1.0-al/A)*i0).toFloat()}
    private fun computeEmuLowShelfRbj(fi:Int,f0:Float,gDb:Float,sr:Float){val freq=f0.coerceIn(10f,sr*0.49f); val A=10.0.pow(gDb/40.0); val w0=2.0*Math.PI*freq/sr; val c=cos(w0); val s=sin(w0); val al=s*0.7071067811865475; val t=2.0*sqrt(A)*al; val a0=(A+1)+(A-1)*c+t; val i0=1.0/a0; emuB0[fi]=(A*((A+1)-(A-1)*c+t)*i0).toFloat(); emuB1[fi]=(2.0*A*((A-1)-(A+1)*c)*i0).toFloat(); emuB2[fi]=(A*((A+1)-(A-1)*c-t)*i0).toFloat(); emuA1[fi]=(-2.0*((A-1)+(A+1)*c)*i0).toFloat(); emuA2[fi]=(((A+1)+(A-1)*c-t)*i0).toFloat()}
    private fun computeEmuPeakingRbj(fi:Int,f0:Float,gDb:Float,q:Float,sr:Float){val freq=f0.coerceIn(10f,sr*0.49f); val A=10.0.pow(gDb/40.0); val w0=2.0*Math.PI*freq/sr; val c=cos(w0); val s=sin(w0); val al=s/(2.0*q); val a0=1.0+al/A; val i0=1.0/a0; emuB0[fi]=((1.0+al*A)*i0).toFloat(); emuB1[fi]=((-2.0*c)*i0).toFloat(); emuB2[fi]=((1.0-al*A)*i0).toFloat(); emuA1[fi]=((-2.0*c)*i0).toFloat(); emuA2[fi]=((1.0-al/A)*i0).toFloat()}
    private fun computeEmuHighShelfRbj(fi:Int,f0:Float,gDb:Float,sr:Float){val freq=f0.coerceIn(10f,sr*0.49f); val A=10.0.pow(gDb/40.0); val w0=2.0*Math.PI*freq/sr; val c=cos(w0); val s=sin(w0); val al=s*0.7071067811865475; val t=2.0*sqrt(A)*al; val a0=(A+1)-(A-1)*c+t; val i0=1.0/a0; emuB0[fi]=(A*((A+1)+(A-1)*c+t)*i0).toFloat(); emuB1[fi]=(-2.0*A*((A-1)+(A+1)*c)*i0).toFloat(); emuB2[fi]=(A*((A+1)+(A-1)*c-t)*i0).toFloat(); emuA1[fi]=(2.0*((A-1)-(A+1)*c)*i0).toFloat(); emuA2[fi]=(((A+1)-(A-1)*c-t)*i0).toFloat()}
    private fun computeEmuLowPassRbj(fi:Int,f0:Float,q:Float,sr:Float){val freq=f0.coerceIn(10f,sr*0.49f); val w0=2.0*Math.PI*freq/sr; val c=cos(w0); val s=sin(w0); val al=s/(2.0*q); val a0=1.0+al; val i0=1.0/a0; val bv=(1.0-c)/2.0; emuB0[fi]=(bv*i0).toFloat(); emuB1[fi]=((1.0-c)*i0).toFloat(); emuB2[fi]=(bv*i0).toFloat(); emuA1[fi]=((-2.0*c)*i0).toFloat(); emuA2[fi]=((1.0-al)*i0).toFloat()}
}
