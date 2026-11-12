package com.estareng.pawpilot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri

/** User-selected local audio. Stops on focus loss or leaving the app. */
class RestMusicPlayer(private val context: Context, private val changed: (String?) -> Unit) {
    private val audio=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var player:MediaPlayer?=null
    private var preparing=false
    val isPreparing: Boolean get() = preparing
    var isPlaying=false;private set
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if(change<0)stop()
        }.build()
    fun play(uri:Uri){
        if(preparing)return
        stop()
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){changed("다른 차량 오디오가 사용 중입니다.");return}
        try {
            val p=MediaPlayer();player=p;preparing=true
            p.setAudioAttributes(attributes);p.setDataSource(context,uri)
            p.setOnPreparedListener {
                if(player===it){preparing=false;it.start();isPlaying=true;changed("휴식 음악을 재생하고 있어요.")}
            }
            p.setOnCompletionListener {stop()}
            p.setOnErrorListener {_,_,_->stop();changed("음악을 재생할 수 없습니다. 다른 파일을 선택해 주세요.");true}
            p.prepareAsync()
            changed("음악을 준비하고 있어요.")
        } catch(_:Exception){stop();changed("음악 파일에 접근할 수 없습니다. 다시 선택해 주세요.")}
    }
    fun stop(){
        val wasActive = player != null || preparing || isPlaying
        player?.release();player=null;preparing=false;isPlaying=false
        audio.abandonAudioFocusRequest(focus)
        changed(if(wasActive) "음악을 멈췄어요." else null)
    }
}
