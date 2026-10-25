package com.dot.capri.intents

sealed interface Cmd {
    data class Play(val query: String, val app: String?) : Cmd
    data class Call(val contact: String) : Cmd
    data class Text(val contact: String, val message: String?) : Cmd
    data class Open(val app: String) : Cmd
    data class Search(val query: String) : Cmd
    data class SeeScreen(val prompt: String) : Cmd
    data class Chat(val text: String) : Cmd
    data class Compliment(val text: String) : Cmd
    data class Mention(val text: String) : Cmd
    object MuteProactivity : Cmd
    object UnmuteProactivity : Cmd
    object ClearHistory : Cmd
    data class Unknown(val raw: String) : Cmd
}
