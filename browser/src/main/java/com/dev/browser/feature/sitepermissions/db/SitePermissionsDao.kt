/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.dev.browser.feature.sitepermissions.db

import androidx.room.*


/**
 * Internal dao for accessing and modifying sitePermissions in the database.
 */
@Dao
internal interface SitePermissionsDao {

    @Insert
    fun insert(entity: SitePermissionsEntity): Long

    @Update
    fun update(entity: SitePermissionsEntity)

    @Query("SELECT * FROM site_permissions ORDER BY saved_at DESC")
    fun getSitePermissions(): List<SitePermissionsEntity>

    @Query("SELECT * FROM site_permissions where origin =:origin LIMIT 1")
    fun getSitePermissionsBy(origin: String): SitePermissionsEntity?

    @Delete
    fun deleteSitePermissions(entity: SitePermissionsEntity)
}
