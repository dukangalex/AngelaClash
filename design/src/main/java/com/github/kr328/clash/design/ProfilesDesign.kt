package com.github.kr328.clash.design

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.databinding.DesignProfilesBinding
import com.github.kr328.clash.design.databinding.DialogProfilesMenuBinding
import com.github.kr328.clash.design.databinding.DialogProfileQrCodeBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import java.util.UUID
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ProfilesDesign(
    context: Context,
    private val scriptOn: (UUID) -> Boolean = { false },
) : Design<ProfilesDesign.Request>(context) {
    sealed class Request {
        object UpdateAll : Request()
        object Create : Request()
        data class Active(val profile: Profile) : Request()
        data class Update(val profile: Profile) : Request()
        data class Edit(val profile: Profile) : Request()
        data class Duplicate(val profile: Profile) : Request()
        data class ShareQrCode(val profile: Profile) : Request()
        data class SetScript(val profile: Profile, val enabled: Boolean) : Request()
        data class Delete(val profile: Profile) : Request()
    }

    private val serviceStore = ServiceStore(context)
    private val binding = DesignProfilesBinding
        .inflate(context.layoutInflater, context.root, false)
    private lateinit var itemTouchHelper: ItemTouchHelper
    private val adapter = ProfileAdapter(
        context,
        this::requestActive,
        this::showMenu,
        onStartDrag = { holder ->
            if (::itemTouchHelper.isInitialized) {
                itemTouchHelper.startDrag(holder)
            }
        },
        // 配置卡上的「脚本 ON」徽标：和首页用同一个判断，避免两处口径不一致。
        scriptState = { profile -> scriptOn(profile.uuid) },
    )

    private var allUpdating: Boolean
        get() = adapter.states.allUpdating;
        set(value) {
            adapter.states.allUpdating = value
        }
    private val rotateAnimation : Animation = AnimationUtils.loadAnimation(context, R.anim.rotate_infinite)

    override val root: View
        get() = binding.root

    /** 脚本开关在菜单里切换后，重画列表以更新「脚本 ON」徽标。 */
    fun refreshScriptBadges() {
        adapter.notifyDataSetChanged()
    }

    suspend fun patchProfiles(profiles: List<Profile>) {
        val sorted = withContext(Dispatchers.Default) {
            serviceStore.sortByProfileOrder(profiles) { it.uuid }.map { it.item }
        }
        adapter.apply {
            patchDataSet(this::profiles, sorted, id = { it.uuid })
        }

        val updatable = withContext(Dispatchers.Default) {
            sorted.any { it.imported && it.type != Profile.Type.File }
        }

        withContext(Dispatchers.Main) {
            binding.updateView.visibility = if (updatable) View.VISIBLE else View.GONE
        }
    }

    suspend fun requestSave(profile: Profile) {
        showToast(R.string.active_unsaved_tips, ToastDuration.Long) {
            setAction(R.string.edit) {
                requests.trySend(Request.Edit(profile))
            }
        }
    }

    fun updateElapsed() {
        adapter.updateElapsed()
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (adapter.moveItem(from, to)) {
                    serviceStore.saveProfileOrder(adapter.profiles.map { it.uuid })
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.animate()
                        .scaleX(1.02f)
                        .scaleY(1.02f)
                        .alpha(0.92f)
                        .setDuration(120)
                        .start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(1.0f)
                    .setDuration(120)
                    .start()
                serviceStore.saveProfileOrder(adapter.profiles.map { it.uuid })
            }
        })

        binding.recyclerView.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
            itemTouchHelper.attachToRecyclerView(it)
        }
    }

    private fun showMenu(profile: Profile) {
        val dialog = AppBottomSheetDialog(context)

        val binding = DialogProfilesMenuBinding
            .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

        binding.master = this
        binding.self = dialog
        binding.profile = profile
        binding.scriptOn = scriptOn(profile.uuid)

        dialog.setContentView(binding.root)
        dialog.show()
    }

    fun requestUpdateAll() {
        allUpdating = true;
        changeUpdateAllButtonStatus()
        requests.trySend(Request.UpdateAll)
    }

    fun finishUpdateAll() {
        allUpdating = false;
        changeUpdateAllButtonStatus()
    }

    fun requestCreate() {
        requests.trySend(Request.Create)
    }

    private fun requestActive(profile: Profile) {
        requests.trySend(Request.Active(profile))
    }

    fun requestUpdate(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Update(profile))

        dialog.dismiss()
    }

    fun requestEdit(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Edit(profile))

        dialog.dismiss()
    }

    fun requestToggleScript(switch: SwitchCompat, profile: Profile) {
        val enabled = !switch.isChecked
        switch.isChecked = enabled
        requests.trySend(Request.SetScript(profile, enabled))
    }

    fun requestDuplicate(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Duplicate(profile))

        dialog.dismiss()
    }

    fun requestDelete(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Delete(profile))

        dialog.dismiss()
    }

    fun requestShareQrCode(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.ShareQrCode(profile))

        dialog.dismiss()
    }

    suspend fun showProfileQrCode(profile: Profile) {
        if (profile.type != Profile.Type.Url || profile.source.isBlank()) return

        val bitmap = try {
            withContext(Dispatchers.Default) {
                val matrix = QRCodeWriter().encode(
                    profile.source, BarcodeFormat.QR_CODE, 768, 768,
                    mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
                )
                val pixels = IntArray(matrix.width * matrix.height) { index ->
                    if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
                }
                Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            }
        } catch (_: WriterException) {
            showToast(R.string.qr_code_generation_failed, ToastDuration.Long)
            return
        }

        withContext(Dispatchers.Main) {
            val binding = DialogProfileQrCodeBinding.inflate(context.layoutInflater)
            binding.qrCode.setImageBitmap(bitmap)

            suspendCancellableCoroutine<Unit> { continuation ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.share_qr_code)
                    .setView(binding.root)
                    .setPositiveButton(R.string.close) { _, _ -> }
                    .setOnDismissListener {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    .show()

                continuation.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    private fun changeUpdateAllButtonStatus() {
        if (allUpdating) {
            binding.updateView.startAnimation(rotateAnimation)
        } else {
            binding.updateView.clearAnimation()
        }
    }
}
