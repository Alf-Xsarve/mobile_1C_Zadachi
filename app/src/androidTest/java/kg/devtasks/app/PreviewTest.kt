package kg.devtasks.app
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import kg.devtasks.app.ui.FilePreview
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class PreviewTest {
 @Test fun imageAndPdfOpenInsideApp(){
  ActivityScenario.launch(MainActivity::class.java).use {scenario ->
   scenario.onActivity {a ->
    val bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888)
    val out=java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);bitmap.recycle()
    FilePreview.show(a,"Фото.png",out.toByteArray())
   }
   onView(withText("Фото.png")).check(matches(isDisplayed()))
   onView(withText("Закрыть")).perform(click())
   scenario.onActivity {a ->
    val doc=android.graphics.pdf.PdfDocument();val page=doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300,400,1).create());doc.finishPage(page)
    val out=java.io.ByteArrayOutputStream();doc.writeTo(out);doc.close();FilePreview.show(a,"Документ.pdf",out.toByteArray())
   }
   onView(withText("Документ.pdf (1/1)")).check(matches(isDisplayed()))
   onView(withText("Закрыть")).perform(click())
  }
 }
 @Test fun docxTextIsVisible(){
  ActivityScenario.launch(MainActivity::class.java).use {scenario ->
   scenario.onActivity {a ->
    val out=java.io.ByteArrayOutputStream();java.util.zip.ZipOutputStream(out).use{zip->zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"));zip.write("<w:document><w:t>Текст документа</w:t></w:document>".toByteArray());zip.closeEntry()}
    FilePreview.show(a,"Текст.docx",out.toByteArray())
   }
   onView(withText("Текст документа")).check(matches(isDisplayed()))
   onView(withText("Закрыть")).perform(click())
  }
 }
}
