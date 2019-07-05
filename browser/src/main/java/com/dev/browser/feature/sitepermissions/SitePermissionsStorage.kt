/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.dev.browser.feature.sitepermissions

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.dev.browser.database.sitepermission.toSitePermissionsEntity
import com.dev.browser.feature.sitepermissions.SitePermissions.Status
import com.dev.browser.feature.sitepermissions.SitePermissions.Status.ALLOWED
import com.dev.browser.feature.sitepermissions.SitePermissionsStorage.Permission.BLUETOOTH
import com.dev.browser.feature.sitepermissions.SitePermissionsStorage.Permission.MICROPHONE
import com.dev.browser.feature.sitepermissions.SitePermissionsStorage.Permission.CAMERA
import com.dev.browser.feature.sitepermissions.SitePermissionsStorage.Permission.LOCAL_STORAGE
import com.dev.browser.feature.sitepermissions.SitePermissionsStorage.Permission.LOCATION
import com.dev.browser.feature.sitepermissions.SitePermissionsStorage.Permission.NOTIFICATION
import com.dev.browser.database.BrowserDatabase
import com.dev.util.Keep

/**
 * A storage implementation to save [SitePermissions].
 *
 */
class SitePermissionsStorage(
    context: Context
) {
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var databaseInitializer = {
        BrowserDatabase.get(context.applicationContext)
    }

    private val database by lazy { databaseInitializer() }

    /**
     * Persists the [sitePermissions] provided as a parameter.
     * @param sitePermissions the [sitePermissions] to be stored.
     */
    internal fun save(sitePermissions: SitePermissions) {
        database
            .sitePermissionsDao()
            .insert(
                sitePermissions.toSitePermissionsEntity()
            )
    }

    /**
     * Replaces an existing SitePermissions with the values of [sitePermissions] provided as a parameter.
     * @param sitePermissions the sitePermissions to be updated.
     */
    fun update(sitePermissions: SitePermissions) {
        database
            .sitePermissionsDao()
            .update(
                sitePermissions.toSitePermissionsEntity()
            )
    }

    /**
     * Finds all SitePermissions that match the [origin].
     * @param origin the site to be used as filter in the search.
     */
    fun findSitePermissionsBy(origin: String): SitePermissions? {
        return database
            .sitePermissionsDao()
            .getSitePermissionsBy(origin)
            ?.toSitePermission()
    }

    /**
     * Finds all SitePermissions grouped by [Permission].
     * @return a map of site grouped by [Permission].
     */
    fun findAllSitePermissionsGroupedByPermission(): Map<Permission, List<SitePermissions>> {
        val sitePermissions = all()
        val map = mutableMapOf<Permission, MutableList<SitePermissions>>()

        sitePermissions.forEach { permission ->
            with(permission) {
                map.putIfAllowed(BLUETOOTH, bluetooth, permission)
                map.putIfAllowed(MICROPHONE, microphone, permission)
                map.putIfAllowed(CAMERA, cameraFront, permission)
                map.putIfAllowed(CAMERA, cameraBack, permission)
                map.putIfAllowed(LOCAL_STORAGE, localStorage, permission)
                map.putIfAllowed(NOTIFICATION, notification, permission)
                map.putIfAllowed(LOCATION, location, permission)
            }
        }
        return map
    }

    /**
     * Deletes all sitePermissions that match the sitePermissions provided as a parameter.
     * @param sitePermissions the sitePermissions to be deleted from the storage.
     */
    fun remove(sitePermissions: SitePermissions) {
        database
            .sitePermissionsDao()
            .deleteSitePermissions(
                sitePermissions.toSitePermissionsEntity()
            )
    }

    private fun all(): List<SitePermissions> {
        return database
            .sitePermissionsDao()
            .getSitePermissions()
            .map {
                it.toSitePermission()
            }
    }

    private fun MutableMap<Permission, MutableList<SitePermissions>>.putIfAllowed(
        permission: Permission,
        status: Status,
        sitePermissions: SitePermissions
    ) {
        if (status == ALLOWED) {
            if (this.containsKey(permission)) {
                this[permission]?.add(sitePermissions)
            } else {
                this[permission] = mutableListOf(sitePermissions)
            }
        }
    }
    @Keep
    enum class Permission {
        MICROPHONE, BLUETOOTH, CAMERA, LOCAL_STORAGE, NOTIFICATION, LOCATION
    }
}
