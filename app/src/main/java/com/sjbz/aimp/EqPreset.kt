package com.sjbz.aimp

data class EqPreset(val name:String, val bands:FloatArray, val mdrc:FloatArray){
    companion object{
        fun harman(): EqPreset {
            return EqPreset("Harman Kardon",
                floatArrayOf(6f,5.8f,5.5f,5f,4.5f,3.8f,2.8f,1.8f,0.8f,0.2f,0f,0f,0f,-0.5f,-0.8f,-0.5f,0f,0.5f,0.8f,1f,1.2f,0.8f,0.5f,0f,-0.5f,-0.2f,0.5f,1f,1.2f,0.8f,0.3f,0f),
                floatArrayOf(2f,0f,-1f,0f,0.5f)
            )
        }
    }
}
