package co.aospa.sense.util

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.UserHandle
import java.io.File

class SenseUserContext(base: Context, private val userId: Int) :
    ContextWrapper(base.createContextAsUser(UserHandle.of(userId), 0)) {

    private val storageContext = base.applicationContext

    override fun getApplicationContext(): Context = this

    override fun getDir(name: String, mode: Int): File =
        storageContext.getDir(userName(name), mode)

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        storageContext.getSharedPreferences(userName(name), mode)

    private fun userName(name: String): String =
        if (userId == UserHandle.USER_SYSTEM) name else "${name}_$userId"
}
