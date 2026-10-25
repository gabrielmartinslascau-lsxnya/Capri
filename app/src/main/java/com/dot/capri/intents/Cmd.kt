package com.dot.capri.intents

enum class MediaAction {
    PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS
}

sealed interface Cmd {
    data class Play(val query: String, val app: String?) : Cmd
    data class MediaControl(val action: MediaAction) : Cmd
    data class Call(val contact: String) : Cmd
    data class Text(val contact: String, val message: String?) : Cmd
    data class Open(val app: String) : Cmd
    data class Search(val query: String) : Cmd
    data class SeeScreen(val prompt: String) : Cmd
    data class SetDefaultApp(val category: String, val appName: String) : Cmd
    data class Chat(val text: String) : Cmd
    data class Compliment(val text: String) : Cmd
    data class Mention(val text: String) : Cmd
    object MuteProactivity : Cmd
    object UnmuteProactivity : Cmd
    object ClearHistory : Cmd
    object GoHome : Cmd
    data class Unknown(val raw: String) : Cmd
}
