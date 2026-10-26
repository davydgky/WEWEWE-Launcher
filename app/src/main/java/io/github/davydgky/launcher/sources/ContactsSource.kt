package io.github.davydgky.launcher.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import io.github.davydgky.launcher.core.SearchItem
import io.github.davydgky.launcher.core.SourceKind
import io.github.davydgky.launcher.core.TextNormalizer
import io.github.davydgky.launcher.search.ContactTarget
import java.util.Locale

/** Contacts by name, nickname, company, e-mail address and phone number. Needs READ_CONTACTS. */
class ContactsSource(private val context: Context) : SearchSource(ID) {

    override fun missing(context: Context): Requirement? =
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) null
        else Requirement.Contacts

    private class Builder(val id: Long, val lookupKey: String, val name: String, val photo: String?, val starred: Boolean) {
        val phones = ArrayList<String>()
        val phoneKeywords = LinkedHashSet<String>()
        val emails = ArrayList<String>()
        val nicknames = ArrayList<String>()
        var company: String? = null
    }

    override suspend fun load(): List<SearchItem> {
        val country = countryIso()
        val contacts = LinkedHashMap<Long, Builder>()
        val projection = arrayOf(
            Data.CONTACT_ID, Data.LOOKUP_KEY, Data.DISPLAY_NAME_PRIMARY, Data.PHOTO_THUMBNAIL_URI,
            Data.STARRED, Data.MIMETYPE, Data.DATA1, Phone.NORMALIZED_NUMBER,
        )
        val kinds = arrayOf(
            StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE,
            Organization.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE,
        )
        context.contentResolver.query(
            Data.CONTENT_URI, projection,
            "${Data.MIMETYPE} IN (?,?,?,?,?)", kinds, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(2)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val contact = contacts.getOrPut(id) {
                    Builder(id, c.getString(1).orEmpty(), name, c.getString(3), c.getInt(4) != 0)
                }
                val value = c.getString(6)?.trim().orEmpty()
                if (value.isEmpty()) continue
                when (c.getString(5)) {
                    Phone.CONTENT_ITEM_TYPE -> {
                        contact.phones += value
                        contact.phoneKeywords += TextNormalizer.digits(value)
                        c.getString(7)?.let { normalized ->
                            contact.phoneKeywords += TextNormalizer.digits(normalized)
                            // The national form, so "0176…" finds a number stored as "+49 176…".
                            PhoneNumberUtils.formatNumber(normalized, country)?.let { contact.phoneKeywords += TextNormalizer.digits(it) }
                        }
                    }
                    Email.CONTENT_ITEM_TYPE -> contact.emails += value
                    Organization.CONTENT_ITEM_TYPE -> if (contact.company == null) contact.company = value
                    Nickname.CONTENT_ITEM_TYPE -> contact.nicknames += value
                }
            }
        }
        return contacts.values.map { contact ->
            val lookupUri = ContactsContract.Contacts.getLookupUri(contact.id, contact.lookupKey)
            SearchItem(
                key = "contact:${contact.lookupKey}",
                kind = SourceKind.Contact,
                title = contact.name,
                subtitle = contact.company ?: contact.phones.firstOrNull() ?: contact.emails.firstOrNull().orEmpty(),
                keywords = contact.nicknames + listOfNotNull(contact.company) + contact.emails +
                    contact.phoneKeywords.filter { it.length >= 3 },
                boost = if (contact.starred) 0.3f else 0f,
                payload = ContactTarget(
                    lookupUri = lookupUri,
                    photoUri = contact.photo?.let(Uri::parse),
                    phone = contact.phones.firstOrNull(),
                    initials = TextNormalizer.tokens(contact.name).take(2).joinToString("") { it.take(1) }.uppercase(),
                ),
            )
        }
    }

    private fun countryIso(): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val iso = telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: telephony?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country
        return iso.uppercase(Locale.ROOT)
    }

    override fun observe(onChange: () -> Unit) = context.observeUri(ContactsContract.Contacts.CONTENT_URI, onChange)

    companion object {
        const val ID = "contacts"
    }
}
