package org.fossify.messages.adapters

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.marginEnd
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewListAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.shareTextIntent
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.usableScreenSize
import org.fossify.commons.helpers.FontHelper
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.commons.views.MyTextView
import org.fossify.messages.R
import org.fossify.messages.activities.NewConversationActivity
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.activities.VCardViewerActivity
import org.fossify.messages.databinding.ItemAttachmentDocumentBinding
import org.fossify.messages.databinding.ItemAttachmentImageBinding
import org.fossify.messages.databinding.ItemAttachmentVcardBinding
import org.fossify.messages.databinding.ItemMessageBinding
import org.fossify.messages.databinding.ItemThreadDateTimeBinding
import org.fossify.messages.databinding.ItemThreadErrorBinding
import org.fossify.messages.databinding.ItemThreadSendingBinding
import org.fossify.messages.databinding.ItemThreadSuccessBinding
import org.fossify.messages.dialogs.DeleteConfirmationDialog
import org.fossify.messages.dialogs.MessageDetailsDialog
import org.fossify.messages.dialogs.ReactionDetailsDialog
import org.fossify.messages.dialogs.SelectTextDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getContactFromAddress
import org.fossify.messages.extensions.isImageMimeType
import org.fossify.messages.extensions.isVCardMimeType
import org.fossify.messages.extensions.isVideoMimeType
import org.fossify.messages.extensions.launchViewIntent
import org.fossify.messages.extensions.startContactDetailsIntent
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.helpers.EmojiReactionHelper
import org.fossify.messages.helpers.EXTRA_VCARD_URI
import org.fossify.messages.helpers.TapbackReaction
import org.fossify.messages.helpers.THREAD_DATE_TIME
import org.fossify.messages.helpers.THREAD_RECEIVED_MESSAGE
import org.fossify.messages.helpers.THREAD_SENT_MESSAGE
import org.fossify.messages.helpers.THREAD_SENT_MESSAGE_ERROR
import org.fossify.messages.helpers.THREAD_SENT_MESSAGE_SENDING
import org.fossify.messages.helpers.THREAD_SENT_MESSAGE_SENT
import org.fossify.messages.helpers.generateStableId
import org.fossify.messages.helpers.setupDocumentPreview
import org.fossify.messages.helpers.setupVCardPreview
import org.fossify.messages.models.Attachment
import org.fossify.messages.models.Message
import org.fossify.messages.models.ThreadItem
import org.fossify.messages.models.ThreadItem.ThreadDateTime
import org.fossify.messages.models.ThreadItem.ThreadError
import org.fossify.messages.models.ThreadItem.ThreadSending
import org.fossify.messages.models.ThreadItem.ThreadSent
import org.joda.time.DateTime

class ThreadAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit,
    val isRecycleBin: Boolean,
    val deleteMessages: (messages: List<Message>, toRecycleBin: Boolean, fromRecycleBin: Boolean) -> Unit,
    val sendReaction: (message: Message, reaction: TapbackReaction, isRemoval: Boolean) -> Unit,
    val bottomBarColor: Int,
) : MyRecyclerViewListAdapter<ThreadItem>(activity, recyclerView, ThreadItemDiffCallback(), itemClick) {
    private var fontSize = activity.getTextSize()
    private val messageList = recyclerView
    private var previousTapbackSelectionKeys = emptySet<Int>()

    @SuppressLint("MissingPermission")
    private val hasMultipleSIMCards = (activity.subscriptionManagerCompat().activeSubscriptionInfoList?.size ?: 0) > 1
    private val maxChatBubbleWidth = (activity.usableScreenSize.x * 0.8f).toInt()
    private val reactionHorizontalOverlap = REACTION_HORIZONTAL_OVERLAP_DP.dpToPx()
    private val reactionVerticalOverlap = REACTION_VERTICAL_OVERLAP_DP.dpToPx()
    private val reactionElevation = REACTION_ELEVATION_DP.dpToPx()

    companion object {
        private const val MAX_MEDIA_HEIGHT_RATIO = 3
        private const val SIM_BITS = 21
        private const val SIM_MASK = (1L shl SIM_BITS) - 1
        private const val REACTION_HORIZONTAL_OVERLAP_DP = 8
        private const val REACTION_VERTICAL_OVERLAP_DP = 4
        private const val REACTION_ELEVATION_DP = 1
        private const val REACTION_CORNER_RADIUS_DP = 18
    }

    init {
        setupDragListener(true)
        setHasStableIds(true)
        messageList.clipChildren = false
        (messageList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    override fun getActionMenuId() = R.menu.cab_thread

    override fun prepareActionMode(menu: Menu) {
        val isOneItemSelected = isOneItemSelected()
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        val hasText = selectedMessages.any { it.body.isNotEmpty() }
        val showSaveAs = getSelectedItems().all {
            it is Message && (it.attachment?.attachments?.size ?: 0) > 0
        } && getSelectedAttachments().isNotEmpty()

        menu.apply {
            findItem(R.id.cab_copy_to_clipboard).isVisible = hasText
            findItem(R.id.cab_save_as).isVisible = showSaveAs
            findItem(R.id.cab_share).isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_forward_message).isVisible = isOneItemSelected
            findItem(R.id.cab_select_text).isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_properties).isVisible = isOneItemSelected
            findItem(R.id.cab_restore).isVisible = isRecycleBin
        }
        refreshTapbackBars()
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_copy_to_clipboard -> copyToClipboard()
            R.id.cab_save_as -> saveAs()
            R.id.cab_share -> shareText()
            R.id.cab_forward_message -> forwardMessage()
            R.id.cab_select_text -> selectText()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_restore -> askConfirmRestore()
            R.id.cab_select_all -> selectAll()
            R.id.cab_properties -> showMessageDetails()
        }
    }

    override fun getSelectableItemCount() = currentList.filterIsInstance<Message>().size

    override fun getIsItemSelectable(position: Int) = !isThreadDateTime(position)

    override fun getItemSelectionKey(position: Int): Int? {
        return (currentList.getOrNull(position) as? Message)?.getSelectionKey()
    }

    override fun getItemKeyPosition(key: Int): Int {
        return currentList.indexOfFirst { (it as? Message)?.getSelectionKey() == key }
    }

    override fun onActionModeCreated() {
        refreshTapbackBars()
    }

    override fun onActionModeDestroyed() {
        refreshTapbackBars()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = when (viewType) {
            THREAD_DATE_TIME -> ItemThreadDateTimeBinding.inflate(layoutInflater, parent, false)
            THREAD_SENT_MESSAGE_ERROR -> ItemThreadErrorBinding.inflate(layoutInflater, parent, false)
            THREAD_SENT_MESSAGE_SENT -> ItemThreadSuccessBinding.inflate(layoutInflater, parent, false)
            THREAD_SENT_MESSAGE_SENDING -> ItemThreadSendingBinding.inflate(layoutInflater, parent, false)
            else -> ItemMessageBinding.inflate(layoutInflater, parent, false)
        }

        return ThreadViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val isClickable = item is ThreadError || item is Message
        val isLongClickable = item is Message
        holder.bindView(item, isClickable, isLongClickable) { itemView, _ ->
            when (item) {
                is ThreadDateTime -> setupDateTime(itemView, item)
                is ThreadError -> setupThreadError(itemView)
                is ThreadSent -> setupThreadSuccess(itemView, item.delivered)
                is ThreadSending -> setupThreadSending(itemView)
                is Message -> setupView(holder, itemView, item)
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemId(position: Int): Long {
        return when (val item = getItem(position)) {
            is Message -> item.getStableId()
            is ThreadDateTime -> {
                val sim = (item.simID.hashCode().toLong() and SIM_MASK)
                val key = (item.date.toLong() shl SIM_BITS) or sim
                generateStableId(THREAD_DATE_TIME, key)
            }
            is ThreadError -> generateStableId(THREAD_SENT_MESSAGE_ERROR, item.messageId)
            is ThreadSending -> generateStableId(THREAD_SENT_MESSAGE_SENDING, item.messageId)
            is ThreadSent -> generateStableId(THREAD_SENT_MESSAGE_SENT, item.messageId)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = getItem(position)) {
            is ThreadDateTime -> THREAD_DATE_TIME
            is ThreadError -> THREAD_SENT_MESSAGE_ERROR
            is ThreadSent -> THREAD_SENT_MESSAGE_SENT
            is ThreadSending -> THREAD_SENT_MESSAGE_SENDING
            is Message -> if (item.isReceivedMessage()) THREAD_RECEIVED_MESSAGE else THREAD_SENT_MESSAGE
        }
    }

    private fun copyToClipboard() {
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        if (selectedMessages.isEmpty()) return

        val textToCopy = if (selectedMessages.size == 1) {
            selectedMessages.first().body
        } else {
            selectedMessages.filter { it.body.isNotEmpty() }.joinToString("\n\n") { message ->
                val format = "${activity.config.dateFormat}, ${activity.getTimeFormat()}"
                val dateTime = DateTime(message.millis()).toString(format)
                val sender = if (message.isReceivedMessage()) message.senderName else activity.getString(R.string.me)
                "[$dateTime] $sender: ${message.body}"
            }
        }

        if (textToCopy.isNotEmpty()) {
            activity.copyToClipboard(textToCopy)
        }
    }

    private fun getSelectedAttachments(): List<Attachment> {
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        return selectedMessages.flatMap { it.attachment?.attachments.orEmpty() }
    }

    private fun saveAs() {
        val attachments = getSelectedAttachments()
        if (attachments.isNotEmpty()) {
            (activity as ThreadActivity).saveMMS(attachments)
        }
    }

    private fun shareText() {
        val firstItem = getSelectedItems().firstOrNull() as? Message ?: return
        activity.shareTextIntent(firstItem.body)
    }

    private fun selectText() {
        val firstItem = getSelectedItems().firstOrNull() as? Message ?: return
        if (firstItem.body.trim().isNotEmpty()) {
            SelectTextDialog(activity, firstItem.body)
        }
    }

    private fun showMessageDetails() {
        val message = getSelectedItems().firstOrNull() as? Message ?: return
        MessageDetailsDialog(activity, message)
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size

        // not sure how we can get UnknownFormatConversionException here, so show the error and hope that someone reports it
        val items = try {
            resources.getQuantityString(R.plurals.delete_messages, itemsCnt, itemsCnt)
        } catch (e: Exception) {
            activity.showErrorToast(e)
            return
        }

        val baseString = if (activity.config.useRecycleBin && !isRecycleBin) {
            org.fossify.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            org.fossify.commons.R.string.deletion_confirmation
        }
        val question = String.format(resources.getString(baseString), items)

        DeleteConfirmationDialog(activity, question, activity.config.useRecycleBin && !isRecycleBin) { skipRecycleBin ->
            ensureBackgroundThread {
                val messagesToRemove = getSelectedItems()
                if (messagesToRemove.isNotEmpty()) {
                    val toRecycleBin = !skipRecycleBin && activity.config.useRecycleBin && !isRecycleBin
                    deleteMessages(messagesToRemove.filterIsInstance<Message>(), toRecycleBin, false)
                }
            }
        }
    }

    private fun askConfirmRestore() {
        val itemsCnt = selectedKeys.size

        // not sure how we can get UnknownFormatConversionException here, so show the error and hope that someone reports it
        val items = try {
            resources.getQuantityString(R.plurals.delete_messages, itemsCnt, itemsCnt)
        } catch (e: Exception) {
            activity.showErrorToast(e)
            return
        }

        val baseString = R.string.restore_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(activity, question) {
            ensureBackgroundThread {
                val messagesToRestore = getSelectedItems()
                if (messagesToRestore.isNotEmpty()) {
                    deleteMessages(messagesToRestore.filterIsInstance<Message>(), false, true)
                }
            }
        }
    }

    private fun forwardMessage() {
        val message = getSelectedItems().firstOrNull() as? Message ?: return
        val attachment = message.attachment?.attachments?.firstOrNull()
        Intent(activity, NewConversationActivity::class.java).apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, message.body)

            if (attachment != null) {
                putExtra(Intent.EXTRA_STREAM, attachment.getUri())
            }

            activity.startActivity(this)
        }
    }

    private fun getSelectedItems(): ArrayList<ThreadItem> {
        return currentList.filter {
            selectedKeys.contains((it as? Message)?.getSelectionKey() ?: 0)
        } as ArrayList<ThreadItem>
    }

    private fun isThreadDateTime(position: Int) = currentList.getOrNull(position) is ThreadDateTime

    private fun refreshTapbackBars() {
        val selectedTapbackKeys = selectedKeys.toSet()
        val changedKeys = previousTapbackSelectionKeys + selectedTapbackKeys
        previousTapbackSelectionKeys = selectedTapbackKeys
        if (changedKeys.isEmpty()) {
            return
        }

        messageList.post {
            changedKeys
                .map(::getItemKeyPosition)
                .filter { position -> position != -1 }
                .forEach(::notifyItemChanged)
        }
    }

    fun updateMessages(
        newMessages: ArrayList<ThreadItem>,
        scrollPosition: Int = -1,
        smoothScroll: Boolean = false
    ) {
        val latestMessages = newMessages.toMutableList()
        submitList(latestMessages) {
            if (scrollPosition != -1) {
                if (smoothScroll) {
                    recyclerView.smoothScrollToPosition(scrollPosition)
                } else {
                    recyclerView.scrollToPosition(scrollPosition)
                }
            }
        }
    }

    private fun setupView(holder: ViewHolder, view: View, message: Message) {
        ItemMessageBinding.bind(view).apply {
            val isSelected = selectedKeys.contains(message.getSelectionKey())
            threadMessageHolder.isSelected = isSelected
            threadMessageBody.apply {
                text = message.body
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                beVisibleIf(message.body.isNotEmpty())
                setOnLongClickListener {
                    holder.viewLongClicked()
                    true
                }

                setOnClickListener {
                    holder.viewClicked(message)
                }
            }

            if (message.isReceivedMessage()) {
                setupReceivedMessageView(messageBinding = this, message = message)
            } else {
                setupSentMessageView(messageBinding = this, message = message)
            }
            setupTapbackReactionBar(
                activity = activity,
                selectedKeys = selectedKeys,
                message = message,
                isRecycleBin = isRecycleBin,
                reactionElevation = reactionElevation,
                textColor = textColor,
                sendReaction = sendReaction,
                bottomBarColor = bottomBarColor,
                finishActionMode = ::finishActMode,
            )
            setupEmojiReactions(messageBinding = this, message = message)

            if (message.attachment?.attachments?.isNotEmpty() == true) {
                threadMessageAttachmentsHolder.beVisible()
                threadMessageAttachmentsHolder.removeAllViews()
                for (attachment in message.attachment.attachments) {
                    val mimetype = attachment.mimetype
                    when {
                        mimetype.isImageMimeType() || mimetype.isVideoMimeType() -> setupImageView(holder, binding = this, message, attachment)
                        mimetype.isVCardMimeType() -> setupVCardView(holder, threadMessageAttachmentsHolder, message, attachment)
                        else -> setupFileView(holder, threadMessageAttachmentsHolder, message, attachment)
                    }

                    threadMessagePlayOutline.beVisibleIf(mimetype.startsWith("video/"))
                }
            } else {
                threadMessageAttachmentsHolder.beGone()
                threadMessagePlayOutline.beGone()
            }
        }
    }

    private fun setupEmojiReactions(messageBinding: ItemMessageBinding, message: Message) {
        val reactions = message.emojiReactions
        messageBinding.threadMessageReactions.apply {
            beVisibleIf(reactions.isNotEmpty())
            if (reactions.isEmpty()) {
                text = ""
                setOnLongClickListener(null)
                return
            }

            val uniqueEmojis = reactions.map { it.emoji }.distinct()
            text = if (reactions.size == 1) {
                uniqueEmojis.first()
            } else {
                "${uniqueEmojis.joinToString("")}\u00A0${reactions.size}"
            }
            setOnLongClickListener {
                showReactionDetails(message)
                true
            }

            val hasOwnReaction = reactions.any { it.isMine }
            val reactionBackgroundColor = if (hasOwnReaction) activity.getProperPrimaryColor() else bottomBarColor
            val reactionTextColor = if (hasOwnReaction) reactionBackgroundColor.getContrastColor() else textColor

            if (message.isReceivedMessage()) {
                background = createRoundedBackground(reactionBackgroundColor)
                elevation = reactionElevation
                translationX = reactionHorizontalOverlap
                translationY = -reactionVerticalOverlap
                setTextColor(reactionTextColor)
                updateLayoutParams<RelativeLayout.LayoutParams> {
                    removeRule(RelativeLayout.END_OF)
                    removeRule(RelativeLayout.ALIGN_PARENT_END)
                    removeRule(RelativeLayout.ALIGN_END)
                    removeRule(RelativeLayout.ALIGN_RIGHT)
                    removeRule(RelativeLayout.ALIGN_START)
                    removeRule(RelativeLayout.ALIGN_LEFT)
                    addRule(RelativeLayout.ALIGN_END, messageBinding.threadMessageBody.id)
                }
            } else {
                background = createRoundedBackground(reactionBackgroundColor)
                elevation = reactionElevation
                translationX = -reactionHorizontalOverlap
                translationY = -reactionVerticalOverlap
                setTextColor(reactionTextColor)
                updateLayoutParams<RelativeLayout.LayoutParams> {
                    removeRule(RelativeLayout.END_OF)
                    removeRule(RelativeLayout.ALIGN_PARENT_END)
                    removeRule(RelativeLayout.ALIGN_END)
                    removeRule(RelativeLayout.ALIGN_RIGHT)
                    removeRule(RelativeLayout.ALIGN_LEFT)
                    addRule(RelativeLayout.ALIGN_START, messageBinding.threadMessageBody.id)
                }
            }
        }
    }

    private fun showReactionDetails(message: Message) {
        val rows = message.emojiReactions.map { reaction ->
            val contactName = if (reaction.isMine) {
                activity.getString(R.string.me)
            } else {
                message.participants
                    .firstOrNull { participant -> participant.doesHavePhoneNumber(reaction.senderPhoneNumber) }
                    ?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: reaction.senderPhoneNumber
            }

            "${reaction.emoji} $contactName"
        }
        ReactionDetailsDialog(activity, rows)
    }

    private fun setupReceivedMessageView(messageBinding: ItemMessageBinding, message: Message) {
        messageBinding.apply {
            with(ConstraintSet()) {
                clone(threadMessageHolder)
                clear(threadMessageWrapper.id, ConstraintSet.END)
                connect(threadMessageWrapper.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                applyTo(threadMessageHolder)
            }

            threadMessageSenderPhoto.beVisible()
            threadMessageSenderPhoto.setOnClickListener {
                val contact = message.getSender()!!
                activity.getContactFromAddress(contact.phoneNumbers.first().normalizedNumber) {
                    if (it != null) {
                        activity.startContactDetailsIntent(it)
                    }
                }
            }

            threadMessageBody.apply {
                background = AppCompatResources.getDrawable(activity, R.drawable.item_received_background)
                setTextColor(textColor)
                setLinkTextColor(activity.getProperPrimaryColor())
            }

            if (!activity.isFinishing && !activity.isDestroyed) {
                val contactLetterIcon = SimpleContactsHelper(activity).getContactLetterIcon(message.senderName)
                val placeholder = contactLetterIcon.toDrawable(activity.resources)

                val options = RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .error(placeholder)
                    .centerCrop()

                Glide.with(activity)
                    .load(message.senderPhotoUri)
                    .placeholder(placeholder)
                    .apply(options)
                    .apply(RequestOptions.circleCropTransform())
                    .into(threadMessageSenderPhoto)
            }
        }
    }

    private fun setupSentMessageView(messageBinding: ItemMessageBinding, message: Message) {
        messageBinding.apply {
            with(ConstraintSet()) {
                clone(threadMessageHolder)
                clear(threadMessageWrapper.id, ConstraintSet.START)
                connect(threadMessageWrapper.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                applyTo(threadMessageHolder)
            }

            val primaryColor = activity.getProperPrimaryColor()
            val contrastColor = primaryColor.getContrastColor()

            threadMessageBody.apply {
                updateLayoutParams<RelativeLayout.LayoutParams> {
                    removeRule(RelativeLayout.END_OF)
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                }

                background = AppCompatResources.getDrawable(activity, R.drawable.item_sent_background)
                background.applyColorFilter(primaryColor)
                setTextColor(contrastColor)
                setLinkTextColor(contrastColor)

                if (message.isScheduled) {
                    typeface = Typeface.create(FontHelper.getTypeface(activity), Typeface.ITALIC)
                    val scheduledDrawable = AppCompatResources.getDrawable(activity, org.fossify.commons.R.drawable.ic_clock_vector)?.apply {
                        applyColorFilter(contrastColor)
                        val size = lineHeight
                        setBounds(0, 0, size, size)
                    }

                    setCompoundDrawables(null, null, scheduledDrawable, null)
                } else {
                    typeface = FontHelper.getTypeface(activity)
                    setCompoundDrawables(null, null, null, null)
                }
            }
        }
    }

    private fun setupImageView(holder: ViewHolder, binding: ItemMessageBinding, message: Message, attachment: Attachment) = binding.apply {
        val mimetype = attachment.mimetype
        val uri = attachment.getUri()

        val imageView = ItemAttachmentImageBinding.inflate(layoutInflater)
        threadMessageAttachmentsHolder.addView(imageView.root)

        val placeholderDrawable = Color.TRANSPARENT.toDrawable()
        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .placeholder(placeholderDrawable)
            .transform(FitCenter())

        Glide.with(root.context)
            .load(uri)
            .apply(options)
            .dontAnimate()
            .override(maxChatBubbleWidth, maxChatBubbleWidth * MAX_MEDIA_HEIGHT_RATIO)
            .downsample(DownsampleStrategy.AT_MOST)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    threadMessagePlayOutline.beGone()
                    threadMessageAttachmentsHolder.removeView(imageView.root)
                    return false
                }

                override fun onResourceReady(dr: Drawable, a: Any, t: Target<Drawable>, d: DataSource, i: Boolean) = false
            })
            .into(imageView.attachmentImage)

        imageView.attachmentImage.updateLayoutParams<ViewGroup.LayoutParams> {
            width = maxChatBubbleWidth
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        imageView.attachmentImage.setOnClickListener {
            if (actModeCallback.isSelectable) {
                holder.viewClicked(message)
            } else {
                activity.launchViewIntent(uri, mimetype, attachment.filename)
            }
        }
        imageView.root.setOnLongClickListener {
            holder.viewLongClicked()
            true
        }
    }

    private fun setupVCardView(holder: ViewHolder, parent: LinearLayout, message: Message, attachment: Attachment) {
        val uri = attachment.getUri()
        val vCardView = ItemAttachmentVcardBinding.inflate(layoutInflater).apply {
            setupVCardPreview(
                activity = activity,
                uri = uri,
                onClick = {
                    if (actModeCallback.isSelectable) {
                        holder.viewClicked(message)
                    } else {
                        val intent = Intent(activity, VCardViewerActivity::class.java).also {
                            it.putExtra(EXTRA_VCARD_URI, uri)
                        }
                        activity.startActivity(intent)
                    }
                },
                onLongClick = { holder.viewLongClicked() }
            )
        }.root

        parent.addView(vCardView)
    }

    private fun setupFileView(holder: ViewHolder, parent: LinearLayout, message: Message, attachment: Attachment) {
        val mimetype = attachment.mimetype
        val uri = attachment.getUri()
        val attachmentView = ItemAttachmentDocumentBinding.inflate(layoutInflater).apply {
            setupDocumentPreview(
                uri = uri,
                title = attachment.filename,
                mimeType = attachment.mimetype,
                onClick = {
                    if (actModeCallback.isSelectable) {
                        holder.viewClicked(message)
                    } else {
                        activity.launchViewIntent(uri, mimetype, attachment.filename)
                    }
                },
                onLongClick = { holder.viewLongClicked() }
            )
        }.root

        parent.addView(attachmentView)
    }

    private fun setupDateTime(view: View, dateTime: ThreadDateTime) {
        ItemThreadDateTimeBinding.bind(view).apply {
            threadDateTime.apply {
                text = (dateTime.date * 1000L).formatDateOrTime(
                    context = context,
                    hideTimeOnOtherDays = false,
                    showCurrentYear = false
                )
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            }
            threadDateTime.setTextColor(textColor)

            threadSimIcon.beVisibleIf(hasMultipleSIMCards)
            threadSimNumber.beVisibleIf(hasMultipleSIMCards)
            if (hasMultipleSIMCards) {
                threadSimNumber.text = dateTime.simID
                threadSimNumber.setTextColor(textColor.getContrastColor())
                threadSimIcon.applyColorFilter(textColor)
            }
        }
    }

    private fun setupThreadSuccess(view: View, isDelivered: Boolean) {
        ItemThreadSuccessBinding.bind(view).apply {
            threadSuccess.setImageResource(if (isDelivered) R.drawable.ic_check_double_vector else org.fossify.commons.R.drawable.ic_check_vector)
            threadSuccess.applyColorFilter(textColor)
        }
    }

    private fun setupThreadError(view: View) {
        val binding = ItemThreadErrorBinding.bind(view)
        binding.threadError.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize - 4)
    }

    private fun setupThreadSending(view: View) {
        ItemThreadSendingBinding.bind(view).threadSending.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            setTextColor(textColor)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            val binding = (holder as ThreadViewHolder).binding
            if (binding is ItemMessageBinding) {
                Glide.with(activity).clear(binding.threadMessageSenderPhoto)
            }
        }
    }

    inner class ThreadViewHolder(val binding: ViewBinding) : ViewHolder(binding.root)

    private fun Int.dpToPx(): Float {
        return this * resources.displayMetrics.density
    }

    private fun createRoundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = REACTION_CORNER_RADIUS_DP.dpToPx()
            setColor(color)
        }
    }
}

private const val TAPBACK_BAR_HORIZONTAL_PADDING_DP = 16
private const val TAPBACK_BAR_VERTICAL_PADDING_DP = 6
private const val TAPBACK_EMOJI_PADDING_DP = 10
private const val TAPBACK_EMOJI_TEXT_SIZE = 24f
private const val TAPBACK_CORNER_RADIUS_DP = 18

private fun ItemMessageBinding.setupTapbackReactionBar(
    activity: BaseSimpleActivity,
    selectedKeys: Collection<Int>,
    message: Message,
    isRecycleBin: Boolean,
    reactionElevation: Float,
    textColor: Int,
    sendReaction: (message: Message, reaction: TapbackReaction, isRemoval: Boolean) -> Unit,
    bottomBarColor: Int,
    finishActionMode: () -> Unit,
) {
    val shouldShowBar = selectedKeys.size == 1 &&
        selectedKeys.contains(message.getSelectionKey()) &&
        !isRecycleBin &&
        message.body.isNotBlank()

    threadMessageReactionBar.apply {
        beVisibleIf(shouldShowBar)
        removeAllViews()
        if (!shouldShowBar) return

        val barHorizontalPadding = TAPBACK_BAR_HORIZONTAL_PADDING_DP.dpToPx(activity).toInt()
        val barVerticalPadding = TAPBACK_BAR_VERTICAL_PADDING_DP.dpToPx(activity).toInt()
        val emojiPadding = TAPBACK_EMOJI_PADDING_DP.dpToPx(activity).toInt()

        background = createTapbackBackground(bottomBarColor, activity)
        elevation = reactionElevation
        setPadding(barHorizontalPadding, barVerticalPadding, barHorizontalPadding, barVerticalPadding)

        updateLayoutParams<RelativeLayout.LayoutParams> {
            val senderPhotoOffset = if (message.isReceivedMessage()) {
                val senderPhotoWidth = threadMessageSenderPhoto.width.takeIf { width -> width > 0 }
                    ?: threadMessageSenderPhoto.layoutParams.width
                senderPhotoWidth + threadMessageSenderPhoto.marginEnd
            } else {
                0
            }
            setMarginStart(senderPhotoOffset)
            setMarginEnd(0)
            removeRule(RelativeLayout.ALIGN_PARENT_END)
            removeRule(RelativeLayout.ALIGN_END)
            removeRule(RelativeLayout.ALIGN_RIGHT)
            removeRule(RelativeLayout.ALIGN_START)
            removeRule(RelativeLayout.ALIGN_LEFT)
            removeRule(RelativeLayout.END_OF)
            addRule(RelativeLayout.BELOW, threadMessageAttachmentsHolder.id)
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.ALIGN_PARENT_END)
        }

        val selectedEmoji = message.emojiReactions.firstOrNull { it.isMine }?.emoji
        EmojiReactionHelper.tapbackReactions.forEach { reaction ->
            addView(
                createTapbackReactionView(
                    activity = activity,
                    reaction = reaction,
                    selectedEmoji = selectedEmoji,
                    emojiPadding = emojiPadding,
                    textColor = textColor,
                    sendReaction = sendReaction,
                    finishActionMode = finishActionMode,
                    message = message,
                )
            )
        }
    }
}

private fun createTapbackReactionView(
    activity: BaseSimpleActivity,
    reaction: TapbackReaction,
    selectedEmoji: String?,
    emojiPadding: Int,
    textColor: Int,
    sendReaction: (message: Message, reaction: TapbackReaction, isRemoval: Boolean) -> Unit,
    finishActionMode: () -> Unit,
    message: Message,
): View {
    val isSelected = selectedEmoji == reaction.emoji
    val emojiView = MyTextView(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        gravity = Gravity.CENTER
        includeFontPadding = true
        text = reaction.emoji
        textSize = TAPBACK_EMOJI_TEXT_SIZE
        setPadding(emojiPadding, emojiPadding, emojiPadding, emojiPadding)

        if (isSelected) {
            val primaryColor = activity.getProperPrimaryColor()
            background = createTapbackBackground(primaryColor, activity)
            setTextColor(primaryColor.getContrastColor())
        } else {
            background = null
            setTextColor(textColor)
        }
    }

    return LinearLayout(activity).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        gravity = Gravity.CENTER
        isClickable = true
        clipChildren = false
        clipToPadding = false
        addView(emojiView)
        setOnClickListener {
            sendReaction(message, reaction, isSelected)
            finishActionMode()
        }
    }
}

private fun createTapbackBackground(color: Int, activity: BaseSimpleActivity): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = TAPBACK_CORNER_RADIUS_DP.dpToPx(activity)
        setColor(color)
    }
}

private fun Int.dpToPx(activity: BaseSimpleActivity): Float {
    return this * activity.resources.displayMetrics.density
}

private class ThreadItemDiffCallback : DiffUtil.ItemCallback<ThreadItem>() {

    override fun areItemsTheSame(oldItem: ThreadItem, newItem: ThreadItem): Boolean {
        if (oldItem::class.java != newItem::class.java) return false
        return when (oldItem) {
            is ThreadError -> oldItem.messageId == (newItem as ThreadError).messageId
            is ThreadSent -> oldItem.messageId == (newItem as ThreadSent).messageId
            is ThreadSending -> oldItem.messageId == (newItem as ThreadSending).messageId
            is Message -> Message.areItemsTheSame(oldItem, newItem as Message)
            is ThreadDateTime -> {
                val new = newItem as ThreadDateTime
                oldItem.date == new.date && oldItem.simID == new.simID
            }
        }
    }

    override fun areContentsTheSame(oldItem: ThreadItem, newItem: ThreadItem): Boolean {
        if (oldItem::class.java != newItem::class.java) return false
        return when (oldItem) {
            is ThreadSending -> true
            is ThreadDateTime -> oldItem.simID == (newItem as ThreadDateTime).simID
            is ThreadError -> oldItem.messageText == (newItem as ThreadError).messageText
            is ThreadSent -> oldItem.delivered == (newItem as ThreadSent).delivered
            is Message -> Message.areContentsTheSame(oldItem, newItem as Message)
        }
    }
}
