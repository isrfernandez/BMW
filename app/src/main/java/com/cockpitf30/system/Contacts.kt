package com.cockpitf30.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Un contacto con su número. */
data class Contact(val name: String, val number: String)

/**
 * Agenda para llamar: los contactos de Android y, si están vacíos, la agenda que el Bluetooth de
 * la radio descarga del móvil (proveedores carbt y ecar de la radio, con columnas sin documentar:
 * se busca la que parezca nombre y la que parezca número).
 */
object Contacts {
    private val RADIO_BOOKS = listOf("content://carbt/phonebook", "content://com.android.ecar.provider.contacts/phonebook")

    fun canRead(context: Context) = granted(context, Manifest.permission.READ_CONTACTS)
    fun canCall(context: Context) = granted(context, Manifest.permission.CALL_PHONE)

    /** Lee la agenda (hilo de fondo: puede tardar con muchos contactos). */
    fun load(context: Context): List<Contact> {
        val android = if (canRead(context)) runCatching { androidContacts(context) }.getOrDefault(emptyList()) else emptyList()
        if (android.isNotEmpty()) return android
        return RADIO_BOOKS.firstNotNullOfOrNull { uri -> runCatching { radioBook(context, uri) }.getOrNull()?.takeIf { it.isNotEmpty() } }.orEmpty()
    }

    /** Llama directamente si hay permiso; si no, abre el marcador con el número puesto. */
    fun call(context: Context, number: String) {
        if (number.isBlank()) return
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val action = if (canCall(context)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        runCatching { context.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun androidContacts(context: Context): List<Contact> =
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { c ->
            buildList { while (c.moveToNext()) add(Contact(c.getString(0) ?: "", c.getString(1) ?: "")) }
                .distinctBy { it.name + it.number.filter(Char::isDigit) }
        } ?: emptyList()

    private fun radioBook(context: Context, uri: String): List<Contact> =
        context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { c ->
            val cols = c.columnNames.map { it.lowercase() }
            val nameCol = cols.indexOfFirst { "name" in it }.takeIf { it >= 0 } ?: return@use emptyList()
            val numCol = cols.indexOfFirst { "num" in it || "phone" in it || "tel" in it }.takeIf { it >= 0 } ?: return@use emptyList()
            buildList {
                while (c.moveToNext()) {
                    val n = runCatching { c.getString(nameCol) }.getOrNull().orEmpty()
                    val p = runCatching { c.getString(numCol) }.getOrNull().orEmpty()
                    if (n.isNotBlank() && p.isNotBlank()) add(Contact(n, p))
                }
            }.distinctBy { it.name + it.number.filter(Char::isDigit) }
        } ?: emptyList()

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
