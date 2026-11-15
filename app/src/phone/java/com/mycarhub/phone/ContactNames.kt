package com.mycarhub.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/**
 * 비슷한 이름 찾기에 쓸 연락처 이름 (폰 빌드, 선택 기능).
 *
 * 사용자가 설정에서 연락처 권한을 허용했을 때만 읽는다. 요청할 때마다 기기 안에서 이름만 읽고
 * 저장·캐시·로그·외부 전송하지 않는다. 권한이 없으면 실제로 받은 메시지의 보낸 사람 이름만으로 찾는다.
 */
object ContactNames {

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** 연락처 표시 이름. 권한이 없거나 읽지 못하면 빈 목록. 백그라운드 스레드에서 호출한다. */
    fun load(context: Context): List<String> {
        if (!hasPermission(context)) return emptyList()
        val names = mutableListOf<String>()
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(names::add)
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return names.distinct()
    }
}
