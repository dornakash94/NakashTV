package tv.nakash

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import tv.nakash.data.local.*

/** Runs on an isolated emulator. Seeds synthetic data; never connects to a subscriber account. */
class TvSmokeRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result=Bundle()
        try {
            val access=EntryPointAccessors.fromApplication(targetContext.applicationContext,SmokeAccess::class.java)
            check(access.account().current()?.username.let { it==null || it=="fixture" }) { "Run only on an isolated test emulator, never a subscriber account" }
            val db=access.database()
            runBlocking { db.clearAllTables() }
            runOnMainSync { access.account().clear() }
            startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitText("שלושה פרטים")
            repeat(3) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);Thread.sleep(120) }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitText("יש למלא")
            screenshot("login")
            val now=System.currentTimeMillis()/1000
            runBlocking {
                db.channels().upsertCategories(listOf(CategoryEntity(1,"live","ישראל","ישראל",0,true),CategoryEntity(1,"vod","סרטים","סרטים",0)))
                db.channels().upsertChannels(listOf(ChannelEntity(100,"ערוץ בדיקה",null,"test",true,"1",0,1,7)))
                db.epg().insertAll(listOf(EpgEntity("test:$now","test",now-600,now+1800,"תוכנית בדיקה","תקציר תוכנית",false)))
                db.vod().upsert(MovieEntity(101,"סרט בדיקה",2026,null,null,"תקציר הסרט","דרמה","שחקן בדיקה","במאי בדיקה",8.0,90,5400,null,null,null,now,"1","mp4",true))
                db.vod().upsertSeries(SeriesEntity(102,"סדרת בדיקה",2026,null,null,"תקציר הסדרה","דרמה",null,8.0,30,now,"1",System.currentTimeMillis()+1000))
                db.vod().upsertSeasons(listOf(SeasonEntity("102:1",102,1,"עונה 1",2,null)))
                db.vod().upsertEpisodes(listOf(EpisodeEntity("201",102,1,1,"פרק ראשון",1800,"mp4",null,null,now),EpisodeEntity("202",102,1,2,"פרק שני",1800,"mp4",null,null,now)))
            }
            runOnMainSync { access.account().save(Account("https://127.0.0.1:9/","fixture","fixture")) }
            waitText("עכשיו בשידור")
            click("סרטים",description=true)
            waitText("סרט בדיקה")
            click("סרט בדיקה")
            waitText("תקציר הסרט")
            click("+ לרשימה שלי")
            waitText("✓ ברשימה שלי")
            screenshot("movie")
            click("סדרות",description=true)
            waitText("סדרת בדיקה")
            click("סדרת בדיקה")
            waitText("פרק ראשון")
            screenshot("series")
            click("לוח שידורים",description=true)
            waitText("תוכנית בדיקה")
            screenshot("guide")
            click("הרשימה שלי",description=true)
            waitText("סרט בדיקה")
            click("הגדרות",description=true)
            waitText("שימוש בשלט")
            screenshot("settings")
            // Clear only this isolated test account, so the delivered emulator opens at sign-in.
            runOnMainSync { access.account().clear() }
            runBlocking { db.clearAllTables() }
            waitText("שלושה פרטים")
            result.putString("stream","PASS: TV login validation, movie detail/favorite, series episodes, guide, my list, settings; seeded fixtures removed.")
            finish(-1,result)
        } catch(t:Throwable) {
            result.putString("stream","FAIL: ${t.stackTraceToString()}")
            finish(0,result)
        }
    }
    private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
    private fun find(text:String,description:Boolean=false):AccessibilityNodeInfo? = nodes(uiAutomation.rootInActiveWindow).firstOrNull {
        (if(description) it.contentDescription?.toString() else it.text?.toString())?.contains(text)==true
    }
    private fun waitText(text:String):AccessibilityNodeInfo {
        repeat(100) { find(text)?.let {return it};Thread.sleep(100) }
        error("Missing UI text: $text. Visible: "+nodes(uiAutomation.rootInActiveWindow).mapNotNull {it.text}.joinToString(" | "))
    }
    private fun click(text:String,description:Boolean=false) {
        var n:AccessibilityNodeInfo?=null
        repeat(100) { if(n==null) {n=find(text,description);if(n==null) Thread.sleep(100)} }
        check(n!=null) {"Missing click target: $text"}
        while(n!=null && !n!!.isClickable) n=n!!.parent
        check(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) {"Not clickable: $text"}
        Thread.sleep(1000)
    }
    private fun screenshot(name:String) {
        Thread.sleep(700)
        uiAutomation.takeScreenshot()?.let { bitmap -> targetContext.getExternalFilesDir(null)!!.resolve("smoke-$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)} }
    }
}
