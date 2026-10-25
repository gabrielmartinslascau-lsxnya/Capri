package com.dot.capri

import com.dot.capri.intents.Cmd
import com.dot.capri.intents.IntentParser
import org.junit.Assert.assertEquals
import org.junit.Test

class IntentParserTest {

    @Test
    fun testParsePlayWithApp() {
        val cmd = IntentParser.parse("play never gonna give you up on youtube")
        assertEquals(Cmd.Play("never gonna give you up", "youtube"), cmd)
    }

    @Test
    fun testParsePlayOnSpotify() {
        val cmd = IntentParser.parse("play lofi on spotify")
        assertEquals(Cmd.Play("lofi", "spotify"), cmd)
    }

    @Test
    fun testParsePlayWithoutApp() {
        val cmd = IntentParser.parse("play chill beats")
        assertEquals(Cmd.Play("chill beats", null), cmd)
    }

    @Test
    fun testParsePlayKnownApp() {
        val cmd1 = IntentParser.parse("play spotify")
        assertEquals(Cmd.Open("spotify"), cmd1)

        val cmd2 = IntentParser.parse("play tiktok")
        assertEquals(Cmd.Open("tiktok"), cmd2)
    }

    @Test
    fun testParseCall() {
        val cmd1 = IntentParser.parse("call mom")
        assertEquals(Cmd.Call("mom"), cmd1)

        val cmd2 = IntentParser.parse("phone 123456789")
        assertEquals(Cmd.Call("123456789"), cmd2)

        val cmd3 = IntentParser.parse("dial john doe")
        assertEquals(Cmd.Call("john doe"), cmd3)
    }

    @Test
    fun testParseOpen() {
        val cmd1 = IntentParser.parse("open tiktok")
        assertEquals(Cmd.Open("tiktok"), cmd1)

        val cmd2 = IntentParser.parse("launch spotify")
        assertEquals(Cmd.Open("spotify"), cmd2)

        val cmd3 = IntentParser.parse("start youtube")
        assertEquals(Cmd.Open("youtube"), cmd3)

        val cmd4 = IntentParser.parse("go to chrome")
        assertEquals(Cmd.Open("chrome"), cmd4)
    }

    @Test
    fun testParseSmartOpenVariations() {
        val cmd1 = IntentParser.parse("open app TikTok")
        assertEquals(Cmd.Open("TikTok"), cmd1)

        val cmd2 = IntentParser.parse("open TikTok app")
        assertEquals(Cmd.Open("TikTok"), cmd2)

        val cmd3 = IntentParser.parse("Reddit")
        assertEquals(Cmd.Open("Reddit"), cmd3)
    }

    @Test
    fun testParseConversationalQuestion() {
        val cmd = IntentParser.parse("can you hack")
        assertEquals(Cmd.Unknown("can you hack"), cmd)
    }

    @Test
    fun testParseSearch() {
        val cmd1 = IntentParser.parse("search cute capybaras")
        assertEquals(Cmd.Search("cute capybaras"), cmd1)

        val cmd2 = IntentParser.parse("google android development")
        assertEquals(Cmd.Search("android development"), cmd2)
    }

    @Test
    fun testParseUnknown() {
        val cmd = IntentParser.parse("do a flip")
        assertEquals(Cmd.Unknown("do a flip"), cmd)
    }
}
