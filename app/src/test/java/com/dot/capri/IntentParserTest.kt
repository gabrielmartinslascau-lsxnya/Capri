package com.dot.capri

import com.dot.capri.intents.Cmd
import com.dot.capri.intents.IntentParser
import com.dot.capri.intents.ScrollDirection
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
    fun testParseConversationalMusicRequests() {
        val cmd1 = IntentParser.parse("can you play some music please")
        assertEquals(Cmd.Play("music", null, isGeneric = true), cmd1)

        val cmd2 = IntentParser.parse("play some music")
        assertEquals(Cmd.Play("music", null, isGeneric = true), cmd2)

        val cmd3 = IntentParser.parse("put on some tunes")
        assertEquals(Cmd.Play("music", null, isGeneric = true), cmd3)

        val cmd4 = IntentParser.parse("shuffle my music")
        assertEquals(Cmd.Play("music", null, isGeneric = true), cmd4)

        val cmd5 = IntentParser.parse("hey capri could you play some songs for me")
        assertEquals(Cmd.Play("music", null, isGeneric = true), cmd5)
    }

    @Test
    fun testParsePlayKnownApp() {
        val cmd1 = IntentParser.parse("play spotify")
        assertEquals(Cmd.Open("spotify"), cmd1)

        val cmd2 = IntentParser.parse("play tiktok")
        assertEquals(Cmd.Open("tiktok"), cmd2)
    }

    @Test
    fun testParseGameRequestsDoNotPlayMusic() {
        val cmd1 = IntentParser.parse("play this game on screen")
        assertEquals(Cmd.Unknown("play this game on screen"), cmd1)

        val cmd2 = IntentParser.parse("play the game")
        assertEquals(Cmd.Unknown("play the game"), cmd2)

        val cmd3 = IntentParser.parse("play on screen")
        assertEquals(Cmd.Unknown("play on screen"), cmd3)
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

    @Test
    fun testParseWriteAndClear() {
        val cmd1 = IntentParser.parse("write on my way")
        assertEquals(Cmd.Write("on my way"), cmd1)

        val cmd2 = IntentParser.parse("type hello world")
        assertEquals(Cmd.Write("hello world"), cmd2)

        val cmd3 = IntentParser.parse("clear this")
        assertEquals(Cmd.ClearField, cmd3)

        val cmd4 = IntentParser.parse("delete it")
        assertEquals(Cmd.ClearField, cmd4)

        val cmd5 = IntentParser.parse("clear the text")
        assertEquals(Cmd.ClearField, cmd5)
    }

    @Test
    fun testParseTypoToleranceNearMissRouting() {
        val cmd1 = IntentParser.parse("opne spoitfy")
        assertEquals(Cmd.Unknown("opne spoitfy"), cmd1)

        val cmd2 = IntentParser.parse("serach capybaras")
        assertEquals(Cmd.Unknown("serach capybaras"), cmd2)

        val cmd3 = IntentParser.parse("writ ehi")
        assertEquals(Cmd.Unknown("writ ehi"), cmd3)
    }

    @Test
    fun testParseSeeScreenVariations() {
        val phrases = listOf(
            "whats on screen",
            "what's on my screen",
            "can you see my screen",
            "can ya see my screen",
            "can u see my screen",
            "what do you see",
            "can you see this",
            "look at my screen",
            "take a look at the screen",
            "tell me what you see",
            "read my screen"
        )
        for (phrase in phrases) {
            val cmd = IntentParser.parse(phrase)
            assert(cmd is Cmd.SeeScreen)
        }
    }

    @Test
    fun testParseClearHistoryVariations() {
        val phrases = listOf(
            "clear history",
            "clear chat",
            "forget everything",
            "wipe memory",
            "reset memory",
            "delete memory",
            "delete your memory",
            "clear memory",
            "wipe your memory",
            "fresh slate"
        )
        for (phrase in phrases) {
            val cmd = IntentParser.parse(phrase)
            assertEquals("Failed for: $phrase", Cmd.ClearHistory, cmd)
        }
    }

    @Test
    fun testParseRepeatScroll() {
        val cmd1 = IntentParser.parse("scroll left twice")
        assert(cmd1 is Cmd.Scroll && cmd1.count == 2)

        val cmd2 = IntentParser.parse("swipe down 3 times")
        assert(cmd2 is Cmd.Scroll && cmd2.count == 3)
    }

    @Test
    fun testParseEmail() {
        val cmd1 = IntentParser.parse("write an email to boss saying let's meet tomorrow")
        assert(cmd1 is Cmd.Email && cmd1.contact == "boss" && cmd1.message == "let's meet tomorrow")

        val cmd2 = IntentParser.parse("send email to john@example.com with subject Meeting saying hello")
        assert(cmd2 is Cmd.Email && cmd2.contact == "john@example.com" && cmd2.subject == "Meeting" && cmd2.message == "hello")
    }

    @Test
    fun testParseTextAboutAndReplyNotifications() {
        val cmdText = IntentParser.parse("text Alex about meeting tomorrow")
        assert(cmdText is Cmd.Text && cmdText.contact == "Alex" && cmdText.message == "meeting tomorrow")

        val cmdNotifs1 = IntentParser.parse("any new notifications?")
        assertEquals(Cmd.CheckNotifications, cmdNotifs1)

        val cmdNotifs2 = IntentParser.parse("do i have any notifications?")
        assertEquals(Cmd.CheckNotifications, cmdNotifs2)

        val cmdReply1 = IntentParser.parse("say to him that im impressed")
        assert(cmdReply1 is Cmd.ReplyNotification && cmdReply1.targetSender == "him" && cmdReply1.replyText == "im impressed")

        val cmdReply2 = IntentParser.parse("tell him that there is no school tomorrow")
        assert(cmdReply2 is Cmd.ReplyNotification && cmdReply2.targetSender == "him" && cmdReply2.replyText == "there is no school tomorrow")

        val cmdReply3 = IntentParser.parse("reply to Alex saying I will be there in 5")
        assert(cmdReply3 is Cmd.ReplyNotification && cmdReply3.targetSender == "Alex" && cmdReply3.replyText == "I will be there in 5")

        val cmdReply4 = IntentParser.parse("reply on my way")
        assert(cmdReply4 is Cmd.ReplyNotification && cmdReply4.targetSender == null && cmdReply4.replyText == "on my way")

        val cmdReply5 = IntentParser.parse("tell him: im impressed")
        assert(cmdReply5 is Cmd.ReplyNotification && cmdReply5.targetSender == "him" && cmdReply5.replyText == "im impressed")

        val cmdReply6 = IntentParser.parse("reply back that I am on my way")
        assert(cmdReply6 is Cmd.ReplyNotification && cmdReply6.replyText == "I am on my way")

        val cmdTxt1 = IntentParser.parse("msg Alex: dinner at 8")
        assert(cmdTxt1 is Cmd.Text && cmdTxt1.contact == "Alex" && cmdTxt1.message == "dinner at 8")

        val cmdTxt2 = IntentParser.parse("txt Sarah about party")
        assert(cmdTxt2 is Cmd.Text && cmdTxt2.contact == "Sarah" && cmdTxt2.message == "party")

        val cmdScrollTypo = IntentParser.parse("scrol down")
        assert(cmdScrollTypo is Cmd.Scroll && cmdScrollTypo.direction == ScrollDirection.DOWN)
    }
}
