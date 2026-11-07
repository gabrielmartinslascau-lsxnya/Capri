package com.dot.capri.util

import android.content.Context
import android.provider.ContactsContract

object ContactUtils {
    data class ContactMatch(val name: String, val number: String)

    fun findMatchingContacts(context: Context, nameQuery: String): List<ContactMatch> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$nameQuery%")
        val list = mutableListOf<ContactMatch>()

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else nameQuery
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex) else ""
                    if (number.isNotBlank()) {
                        list.add(ContactMatch(name, number))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.distinctBy { it.name to it.number }
    }

    fun findPhoneNumberByName(context: Context, nameQuery: String): String? {
        return findMatchingContacts(context, nameQuery).firstOrNull()?.number
    }
}
