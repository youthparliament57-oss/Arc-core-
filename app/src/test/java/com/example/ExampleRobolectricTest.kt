package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AR Camera", appName)
    assertEquals("Tracking", context.getString(R.string.status_ar_tracking))
    assertEquals("Move your phone slowly to scan the environment.", context.getString(R.string.feedback_scan_prompt))
    assertEquals("Surface detected", context.getString(R.string.feedback_surface_detected))
  }
}
