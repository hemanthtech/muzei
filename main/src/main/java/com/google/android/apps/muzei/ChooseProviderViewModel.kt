/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei

import android.annotation.SuppressLint
import android.app.Application
import android.arch.lifecycle.AndroidViewModel
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import com.google.android.apps.muzei.room.InstalledProvidersLiveData
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.room.getComponentName
import com.google.android.apps.muzei.room.getProviderDescription
import com.google.android.apps.muzei.sources.SourceArtProvider
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newSingleThreadContext
import net.nurik.roman.muzei.R

data class ProviderInfo(
        val componentName: ComponentName,
        val title: String,
        val description: String?,
        val currentArtworkUri: Uri?,
        val icon: Drawable,
        val setupActivity: ComponentName?,
        val settingsActivity: ComponentName?,
        val selected: Boolean
) {
    constructor(
            packageManager: PackageManager,
            providerInfo: android.content.pm.ProviderInfo,
            description: String?,
            currentArtworkUri: Uri?,
            selected: Boolean
    ) : this(
                ComponentName(providerInfo.packageName, providerInfo.name),
                providerInfo.loadLabel(packageManager).toString(),
                description,
                currentArtworkUri,
                providerInfo.loadIcon(packageManager),
                providerInfo.metaData?.getString("setupActivity")?.run {
                    ComponentName(providerInfo.packageName, this)
                },
                providerInfo.metaData?.getString("settingsActivity")?.run {
                    ComponentName(providerInfo.packageName, this)
                },
                selected)
}

class ChooseProviderViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"
    }

    private val currentProviders = HashMap<ComponentName, ProviderInfo>()
    private var activeProvider : Provider? = null

    private val singleThreadContext = newSingleThreadContext("ChooseProvider")

    @SuppressLint("InlinedApi")
    val playStoreIntent: Intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("http://play.google.com/store/search?q=Muzei&c=apps" +
                    "&referrer=utm_source%3Dmuzei" +
                    "%26utm_medium%3Dapp" +
                    "%26utm_campaign%3Dget_more_sources"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            .setPackage(PLAY_STORE_PACKAGE_NAME)
    val playStoreComponentName: ComponentName? = playStoreIntent.resolveActivity(
            application.packageManager)

    private val sourceArtProvider = ComponentName(application, SourceArtProvider::class.java)

    private val comparator = Comparator<ProviderInfo> { p1, p2 ->
        // Get More Sources should always be at the end of the list
        if (p1.componentName == playStoreComponentName) {
            return@Comparator 1
        } else if (p2.componentName == playStoreComponentName) {
            return@Comparator -1
        }
        // The SourceArtProvider should always the last provider listed
        if (p1.componentName == sourceArtProvider) {
            return@Comparator 1
        } else if (p2.componentName == sourceArtProvider) {
            return@Comparator -1
        }
        // Then put providers from Muzei on top
        val pn1 = p1.componentName.packageName
        val pn2 = p2.componentName.packageName
        if (pn1 != pn2) {
            if (application.packageName == pn1) {
                return@Comparator -1
            } else if (application.packageName == pn2) {
                return@Comparator 1
            }
        }
        // Finally, sort providers by their title
        p1.title.compareTo(p2.title)
    }

    private suspend fun updateProviders(providerInfos: List<android.content.pm.ProviderInfo>) {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val newProviders = HashMap<ComponentName, ProviderInfo>().apply {
            putAll(currentProviders)
        }
        val existingProviders = HashSet(currentProviders.values)
        existingProviders.removeAll {
            it.componentName == playStoreComponentName
        }
        for (providerInfo in providerInfos) {
            val componentName = providerInfo.getComponentName()
            existingProviders.removeAll { it.componentName == componentName }
            val selected = componentName == activeProvider?.componentName
            val description = componentName.getProviderDescription(context)
            val currentArtwork = MuzeiDatabase.getInstance(context).artworkDao()
                    .getCurrentArtworkForProvider(componentName)
            newProviders[componentName] = ProviderInfo(pm, providerInfo,
                    description, currentArtwork?.imageUri, selected)
        }
        // Remove providers that weren't found in the providerInfos
        existingProviders.forEach {
            newProviders.remove(it.componentName)
        }
        if (playStoreComponentName != null &&
                newProviders[playStoreComponentName] == null) {
            newProviders[playStoreComponentName] = ProviderInfo(
                    playStoreComponentName,
                    context.getString(R.string.get_more_sources),
                    context.getString(R.string.get_more_sources_description),
                    null,
                    pm.getActivityLogo(playStoreIntent)
                            ?: pm.getApplicationIcon(PLAY_STORE_PACKAGE_NAME),
                    null,
                    null,
                    false)
        }
        currentProviders.clear()
        currentProviders.putAll(newProviders)
        mutableProviders.postValue(currentProviders.values.sortedWith(comparator))
    }

    private val mutableProviders : MutableLiveData<List<ProviderInfo>> = object : MutableLiveData<List<ProviderInfo>>() {
        val allProvidersLiveData = InstalledProvidersLiveData(application)
        val allProvidersObserver = Observer<List<android.content.pm.ProviderInfo>> { providerInfos ->
            if (providerInfos != null) {
                launch(singleThreadContext) {
                    updateProviders(providerInfos)
                    launch(UI) {
                        startObserving()
                    }
                }
            }
        }
        val currentProviderLiveData = MuzeiDatabase.getInstance(application).providerDao()
                .currentProvider
        val currentProviderObserver = Observer<Provider?> { provider ->
            activeProvider = provider
            if (provider != null) {
                currentProviders.forEach {
                    val newlySelected = it.key == provider.componentName
                    if (it.value.selected != newlySelected) {
                        currentProviders[it.key] = it.value.copy(selected = newlySelected)
                    }
                }
                value = currentProviders.values.sortedWith(comparator)
            }
        }
        val currentArtworkByProviderLiveData = MuzeiDatabase.getInstance(application).artworkDao()
                .currentArtworkByProvider
        val currentArtworkByProviderObserver = Observer<List<com.google.android.apps.muzei.room.Artwork>> { artworkByProvider ->
            if (artworkByProvider != null) {
                val artworkMap = HashMap<ComponentName, Uri>()
                artworkByProvider.forEach { artwork ->
                    artworkMap[artwork.providerComponentName] = artwork.imageUri
                }
                currentProviders.forEach {
                    currentProviders[it.key] = it.value.copy(currentArtworkUri = artworkMap[it.key])
                }
                value = currentProviders.values.sortedWith(comparator)
            }
        }

        override fun onActive() {
            allProvidersLiveData.observeForever(allProvidersObserver)
        }

        fun startObserving() {
            if (hasActiveObservers() && !currentArtworkByProviderLiveData.hasObservers()) {
                currentProviderLiveData.observeForever(currentProviderObserver)
                currentArtworkByProviderLiveData.observeForever(currentArtworkByProviderObserver)
            }
        }

        override fun onInactive() {
            if (currentArtworkByProviderLiveData.hasObservers()) {
                currentArtworkByProviderLiveData.removeObserver(currentArtworkByProviderObserver)
                currentProviderLiveData.removeObserver(currentProviderObserver)
            }
            allProvidersLiveData.removeObserver(allProvidersObserver)
        }
    }

    val providers : LiveData<List<ProviderInfo>> = mutableProviders

    internal fun refreshDescription(componentName: ComponentName) {
        launch {
            val updatedDescription = componentName.getProviderDescription(getApplication())
            currentProviders[componentName]?.let { providerInfo ->
                if (providerInfo.description != updatedDescription) {
                    currentProviders[componentName] =
                            providerInfo.copy(description = updatedDescription)
                    mutableProviders.postValue(currentProviders.values.sortedWith(comparator))
                }
            }
        }
    }
}
