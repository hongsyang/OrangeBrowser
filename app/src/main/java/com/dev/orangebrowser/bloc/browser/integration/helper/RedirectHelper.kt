package com.dev.orangebrowser.bloc.browser.integration.helper

import com.dev.browser.session.Session
import com.dev.orangebrowser.databinding.FragmentBrowserBinding

//先截图，再跳转
fun redirect(binding:FragmentBrowserBinding,session:Session, runnable: Runnable){
    //重置enterFullScreenMode标志
    session.enterFullScreenMode=false
    //等待一段事件后跳转
    binding.fragmentContainer.postDelayed({
        runnable.run()
    }, 50)
}