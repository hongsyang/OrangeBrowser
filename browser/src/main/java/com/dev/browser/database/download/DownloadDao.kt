/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.dev.browser.database.download

import androidx.room.*


/**
 * 访问历史记录Dao
 */
@Dao
interface DownloadDao {

    @Insert
    fun insert(entity: DownloadEntity): Long

    @Update
    fun update(entity: DownloadEntity)
    @Query("UPDATE download  SET status=:status , content_length=:length WHERE url=:url")
    fun updateStatus(url:String,status:Int,length:Long)
    @Query("UPDATE download  SET local_poster=:localPoster,file_type=:type WHERE url=:url")
    fun updateLocalPosterAndType(url:String, localPoster:String,type:Int)
    @Query("UPDATE download  SET file_type=:type WHERE url=:url")
    fun updateType(url:String,type:Int)
    @Query("SELECT * FROM download WHERE file_type=:type ORDER BY date DESC")
    fun getDownloadByType(type:Int): List<DownloadEntity>
    @Query("SELECT * FROM download ORDER BY date DESC")
    fun getAll(): List<DownloadEntity>
    @Delete
    fun delete(entity: DownloadEntity)
    @Query("DELETE  FROM download")
    fun clear()
    //获取未完成的下载
    @Query("SELECT * FROM download WHERE status=3 ORDER BY date DESC")
    fun getUnFinished():List<DownloadEntity>
}
