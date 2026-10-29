package com.dot.capri.intents

enum class MediaAction {
    PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS
}

enum class VolumeDirection {
    UP, DOWN, MUTE, UNMUTE
}

enum class ScrollDirection {
    UP, DOWN, LEFT, RIGHT
}

sealed interface Cmd {
    data class Play(val query: String, val app: String?) : Cmd
    data class MediaControl(val action: MediaAction) : Cmd
    data class Volume(val direction: VolumeDirection) : Cmd
    data class TypeText(val text: String) : Cmd
    data class Write(val text: String) : Cmd
    object ClearField : Cmd
    data class Call(val contact: String) : Cmd
    data class Text(val contact: String, val message: String?) : Cmd
    data class Open(val app: String) : Cmd
    data class Search(val query: String) : Cmd
    data class SeeScreen(val prompt: String) : Cmd
    data class Scroll(val direction: ScrollDirection, val count: Int = 1) : Cmd
    data class Tap(val target: String) : Cmd
    data class SetDefaultApp(val category: String, val appName: String) : Cmd
    data class Chat(val text: String) : Cmd
    data class Compliment(val text: String) : Cmd
    data class Mention(val text: String) : Cmd
    object MuteProactivity : Cmd
    object UnmuteProactivity : Cmd
    object ClearHistory : Cmd
    object GoHome : Cmd
    object CheckNotifications : Cmd
    data class EasterEgg(val response: String) : Cmd
    data class Unknown(val raw: String) : Cmd
}
