package in.gndec.sunehag.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.ActionBar.Tab;
import android.app.ActionBar.TabListener;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.ListFragment;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Pair;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.Checkable;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import in.gndec.sunehag.Config;
import in.gndec.sunehag.R;
import in.gndec.sunehag.entities.Account;
import in.gndec.sunehag.entities.Bookmark;
import in.gndec.sunehag.entities.Contact;
import in.gndec.sunehag.entities.Conversation;
import in.gndec.sunehag.entities.ListItem;
import in.gndec.sunehag.entities.Presence;
import in.gndec.sunehag.services.XmppConnectionService.OnRosterUpdate;
import in.gndec.sunehag.ui.adapter.ListItemAdapter;
import in.gndec.sunehag.utils.XmppUri;
import in.gndec.sunehag.xmpp.OnUpdateBlocklist;
import in.gndec.sunehag.xmpp.XmppConnection;
import in.gndec.sunehag.xmpp.jid.InvalidJidException;
import in.gndec.sunehag.xmpp.jid.Jid;

public class StartConversationActivity extends XmppActivity implements OnRosterUpdate, OnUpdateBlocklist {

    public int conference_context_id;
    public int contact_context_id;
    private Tab mContactsTab;
    private Tab mConferencesTab;
    private ViewPager mViewPager;
    private ListPagerAdapter mListPagerAdapter;
    private List<ListItem> contacts = new ArrayList<>();
    private ArrayAdapter<ListItem> mContactsAdapter;
    private List<ListItem> conferences = new ArrayList<>();
    private ArrayAdapter<ListItem> mConferenceAdapter;
    private List<String> mActivatedAccounts = new ArrayList<>();
    private List<String> mKnownHosts;
    private List<String> mKnownConferenceHosts;
    private Invite mPendingInvite = null;
    private EditText mSearchEditText;
    private AtomicBoolean mRequestedContactsPermission = new AtomicBoolean(false);
    private final int REQUEST_SYNC_CONTACTS = 0x3b28cf;
    private final int REQUEST_CREATE_CONFERENCE = 0x3b39da;
    private Dialog mCurrentDialog = null;

    private MenuItem.OnActionExpandListener mOnActionExpandListener = new MenuItem.OnActionExpandListener() {

        @Override
        public boolean onMenuItemActionExpand(MenuItem item) {
            mSearchEditText.post(new Runnable() {

                @Override
                public void run() {
                    mSearchEditText.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(mSearchEditText,
                            InputMethodManager.SHOW_IMPLICIT);
                }
            });

            return true;
        }

        @Override
        public boolean onMenuItemActionCollapse(MenuItem item) {
            hideKeyboard();
            mSearchEditText.setText("");
            filter(null);
            return true;
        }
    };
    private boolean mHideOfflineContacts = false;
    private TabListener mTabListener = new TabListener() {

        @Override
        public void onTabUnselected(Tab tab, FragmentTransaction ft) {
            return;
        }

        @Override
        public void onTabSelected(Tab tab, FragmentTransaction ft) {
            mViewPager.setCurrentItem(tab.getPosition());
            onTabChanged();
        }

        @Override
        public void onTabReselected(Tab tab, FragmentTransaction ft) {
            return;
        }
    };
    private ViewPager.SimpleOnPageChangeListener mOnPageChangeListener = new ViewPager.SimpleOnPageChangeListener() {
        @Override
        public void onPageSelected(int position) {
            if (getActionBar() != null) {
                getActionBar().setSelectedNavigationItem(position);
            }
            onTabChanged();
        }
    };
    private TextWatcher mSearchTextWatcher = new TextWatcher() {

        @Override
        public void afterTextChanged(Editable editable) {
            filter(editable.toString());
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count,
                                      int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    };

    private TextView.OnEditorActionListener mSearchDone = new TextView.OnEditorActionListener() {
        @Override
        public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
            int pos = getActionBar().getSelectedNavigationIndex();
            if (pos == 0) {
                if (contacts.size() == 1) {
                    openConversationForContact((Contact) contacts.get(0));
                    return true;
                }
            } else {
                if (conferences.size() == 1) {
                    openConversationsForBookmark((Bookmark) conferences.get(0));
                    return true;
                }
            }
            hideKeyboard();
            mListPagerAdapter.requestFocus(pos);
            return true;
        }
    };
    private MenuItem mMenuSearchView;
    private ListItemAdapter.OnTagClickedListener mOnTagClickedListener = new ListItemAdapter.OnTagClickedListener() {
        @Override
        public void onTagClicked(String tag) {
            if (mMenuSearchView != null) {
                mMenuSearchView.expandActionView();
                mSearchEditText.setText("");
                mSearchEditText.append(tag);
                filter(tag);
            }
        }
    };
    private String mInitialJid;
    private Pair<Integer, Intent> mPostponedActivityResult;
    private UiCallback<Conversation> mAdhocConferenceCallback = new UiCallback<Conversation>() {
        @Override
        public void success(final Conversation conversation) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hideToast();
                    switchToConversation(conversation);
                }
            });
        }

        @Override
        public void error(final int errorCode, Conversation object) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    replaceToast(getString(errorCode));
                }
            });
        }

        @Override
        public void userInputRequried(PendingIntent pi, Conversation object) {

        }
    };
    private Toast mToast;

    protected void hideToast() {
        if (mToast != null) {
            mToast.cancel();
        }
    }

    protected void replaceToast(String msg) {
        hideToast();
        mToast = Toast.makeText(this, msg, Toast.LENGTH_LONG);
        mToast.show();
    }

    @Override
    public void onRosterUpdate() {
        this.refreshUi();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_conversation);
        mViewPager = (ViewPager) findViewById(R.id.start_conversation_view_pager);
        ActionBar actionBar = getActionBar();
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        mContactsTab = actionBar.newTab().setText(R.string.contacts)
                .setTabListener(mTabListener);
        mConferencesTab = actionBar.newTab().setText(R.string.conferences)
                .setTabListener(mTabListener);
        actionBar.addTab(mContactsTab);
        actionBar.addTab(mConferencesTab);

        mViewPager.setOnPageChangeListener(mOnPageChangeListener);
        mListPagerAdapter = new ListPagerAdapter(getFragmentManager());
        mViewPager.setAdapter(mListPagerAdapter);

        mConferenceAdapter = new ListItemAdapter(this, conferences);
        mContactsAdapter = new ListItemAdapter(this, contacts);
        ((ListItemAdapter) mContactsAdapter).setOnTagClickedListener(this.mOnTagClickedListener);
        this.mHideOfflineContacts = getPreferences().getBoolean("hide_offline", false);

    }

    @Override
    public void onStart() {
        super.onStart();
        final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        } else {
            askForContactsPermissions();
        }
    }

    @Override
    public void onStop() {
        if (mCurrentDialog != null) {
            mCurrentDialog.dismiss();
        }
        super.onStop();
    }

    protected void openConversationForContact(int position) {
        Contact contact = (Contact) contacts.get(position);
        openConversationForContact(contact);
    }

    protected void openConversationForContact(Contact contact) {
        Conversation conversation = xmppConnectionService
                .findOrCreateConversation(contact.getAccount(),
                        contact.getJid(), false);
        switchToConversation(conversation);
    }

    protected void openConversationForContact() {
        int position = contact_context_id;
        openConversationForContact(position);
    }

    protected void openConversationForBookmark() {
        openConversationForBookmark(conference_context_id);
    }

    protected void openConversationForBookmark(int position) {
        Bookmark bookmark = (Bookmark) conferences.get(position);
        openConversationsForBookmark(bookmark);
    }

    protected void openConversationsForBookmark(Bookmark bookmark) {
        Jid jid = bookmark.getJid();
        if (jid == null) {
            Toast.makeText(this, R.string.invalid_jid, Toast.LENGTH_SHORT).show();
            return;
        }
        Conversation conversation = xmppConnectionService.findOrCreateConversation(bookmark.getAccount(), jid, true);
        conversation.setBookmark(bookmark);
        if (!conversation.getMucOptions().online()) {
            xmppConnectionService.joinMuc(conversation);
        }
        if (!bookmark.autojoin() && getPreferences().getBoolean("autojoin", true)) {
            bookmark.setAutojoin(true);
            xmppConnectionService.pushBookmarks(bookmark.getAccount());
        }
        switchToConversation(conversation);
    }

    protected void openDetailsForContact() {
        int position = contact_context_id;
        Contact contact = (Contact) contacts.get(position);
        switchToContactDetails(contact);
    }

    protected void toggleContactBlock() {
        final int position = contact_context_id;
        BlockContactDialog.show(this, xmppConnectionService, (Contact) contacts.get(position));
    }

    protected void deleteContact() {
        final int position = contact_context_id;
        final Contact contact = (Contact) contacts.get(position);
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.action_delete_contact);
        builder.setMessage(getString(R.string.remove_contact_text,
                contact.getDisplayJid()));
        builder.setPositiveButton(R.string.delete, new OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                xmppConnectionService.deleteContactOnServer(contact);
                filter(mSearchEditText.getText().toString());
            }
        });
        builder.create().show();
    }

    protected void deleteConference() {
        int position = conference_context_id;
        final Bookmark bookmark = (Bookmark) conferences.get(position);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setTitle(R.string.delete_bookmark);
        builder.setMessage(getString(R.string.remove_bookmark_text,
                bookmark.getDisplayJid()));
        builder.setPositiveButton(R.string.delete, new OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                bookmark.unregisterConversation();
                Account account = bookmark.getAccount();
                account.getBookmarks().remove(bookmark);
                xmppConnectionService.pushBookmarks(account);
                filter(mSearchEditText.getText().toString());
            }
        });
        builder.create().show();

    }

    @SuppressLint("InflateParams")
    protected void showCreateContactDialog(final String prefilledJid, final Invite invite) {
        EnterJidDialog dialog = new EnterJidDialog(
                this, mKnownHosts, mActivatedAccounts,
                getString(R.string.create_contact), getString(R.string.create),
                prefilledJid, null, invite == null || !invite.hasFingerprints());
        dialog.setOnEnterJidDialogPositiveListener(new EnterJidDialog.OnEnterJidDialogPositiveListener() {
            @Override
            public boolean onEnterJidDialogPositive(Jid accountJid, Jid contactJid) throws EnterJidDialog.JidError {
                if (!xmppConnectionServiceBound) {
                    return false;
                }

                final Account account = xmppConnectionService.findAccountByJid(accountJid);
                if (account == null) {
                    return true;
                }

                final Contact contact = account.getRoster().getContact(contactJid);
                if (contact.showInRoster()) {
                    throw new EnterJidDialog.JidError(getString(R.string.contact_already_exists));
                } else {
                    xmppConnectionService.createContact(contact);
                    if (invite != null && invite.hasFingerprints()) {
                        xmppConnectionService.verifyFingerprints(contact,invite.getFingerprints());
                    }
                    switchToConversation(contact);
                    return true;
                }
            }
        });

        mCurrentDialog = dialog.show();
    }

    @SuppressLint("InflateParams")
    protected void showJoinConferenceDialog(final String prefilledJid) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.join_conference);
        final View dialogView = getLayoutInflater().inflate(R.layout.join_conference_dialog, null);
        final Spinner spinner = (Spinner) dialogView.findViewById(R.id.account);
        final AutoCompleteTextView jid = (AutoCompleteTextView) dialogView.findViewById(R.id.jid);
        final TextView jabberIdDesc = (TextView) dialogView.findViewById(R.id.jabber_id);
        jabberIdDesc.setText(R.string.conference_address);
        jid.setHint(R.string.conference_address_example);
        //jid.setAdapter(new KnownHostsAdapter(this, R.layout.simple_list_item, mKnownConferenceHosts));
        if (prefilledJid != null) {
            jid.append(prefilledJid);
        }
        populateAccountSpinner(this, mActivatedAccounts, spinner);
        final Checkable bookmarkCheckBox = (CheckBox) dialogView
                .findViewById(R.id.bookmark);
        builder.setView(dialogView);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.join, null);
        final AlertDialog dialog = builder.create();
        dialog.show();
        mCurrentDialog = dialog;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                new View.OnClickListener() {

                    @Override
                    public void onClick(final View v) {
                        if (!xmppConnectionServiceBound) {
                            return;
                        }
                        final Account account = getSelectedAccount(spinner);
                        if (account == null) {
                            return;
                        }
                        final Jid conferenceJid;
                        try {
                            conferenceJid = Jid.fromString(jid.getText().toString()+"@conference."+Config.DOMAIN_LOCK);
                        } catch (final InvalidJidException e) {
                            jid.setError(getString(R.string.invalid_jid));
                            return;
                        }

                        if (bookmarkCheckBox.isChecked()) {
                            if (account.hasBookmarkFor(conferenceJid)) {
                                jid.setError(getString(R.string.bookmark_already_exists));
                            } else {
                                final Bookmark bookmark = new Bookmark(account, conferenceJid.toBareJid());
                                bookmark.setAutojoin(getPreferences().getBoolean("autojoin", true));
                                String nick = conferenceJid.getResourcepart();
                                if (nick != null && !nick.isEmpty()) {
                                    bookmark.setNick(nick);
                                }
                                account.getBookmarks().add(bookmark);
                                xmppConnectionService.pushBookmarks(account);
                                final Conversation conversation = xmppConnectionService
                                        .findOrCreateConversation(account,
                                                conferenceJid, true);
                                conversation.setBookmark(bookmark);
                                if (!conversation.getMucOptions().online()) {
                                    xmppConnectionService.joinMuc(conversation);
                                }
                                dialog.dismiss();
                                mCurrentDialog = null;
                                switchToConversation(conversation);
                            }
                        } else {
                            final Conversation conversation = xmppConnectionService
                                    .findOrCreateConversation(account,
                                            conferenceJid, true);
                            if (!conversation.getMucOptions().online()) {
                                xmppConnectionService.joinMuc(conversation);
                            }
                            dialog.dismiss();
                            mCurrentDialog = null;
                            switchToConversation(conversation);
                        }
                    }
                });
    }

    private void showCreateConferenceDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.create_conference);
        final View dialogView = getLayoutInflater().inflate(R.layout.create_conference_dialog, null);
        final Spinner spinner = (Spinner) dialogView.findViewById(R.id.account);
        final EditText subject = (EditText) dialogView.findViewById(R.id.subject);
        populateAccountSpinner(this, mActivatedAccounts, spinner);
        builder.setView(dialogView);
        builder.setPositiveButton(R.string.choose_participants, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (!xmppConnectionServiceBound) {
                    return;
                }
                if( subject.getText().toString().trim().isEmpty()){
                    return;
                }
                final Account account = getSelectedAccount(spinner);
                if (account == null) {
                    return;
                }
                Intent intent = new Intent(getApplicationContext(), ChooseContactActivity.class);
                intent.putExtra("multiple", true);
                intent.putExtra("show_enter_jid", false);
                intent.putExtra("subject", subject.getText().toString());
                intent.putExtra(EXTRA_ACCOUNT, account.getJid().toBareJid().toString());
                intent.putExtra(ChooseContactActivity.EXTRA_TITLE_RES_ID, R.string.choose_participants);
                startActivityForResult(intent, REQUEST_CREATE_CONFERENCE);
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        mCurrentDialog = builder.create();
        mCurrentDialog.show();
    }

    private Account getSelectedAccount(Spinner spinner) {
        if (!spinner.isEnabled()) {
            return null;
        }
        Jid jid;
        try {
            if (Config.DOMAIN_LOCK != null) {
                jid = Jid.fromParts((String) spinner.getSelectedItem(), Config.DOMAIN_LOCK, null);
            } else {
                jid = Jid.fromString((String) spinner.getSelectedItem());
            }
        } catch (final InvalidJidException e) {
            return null;
        }
        return xmppConnectionService.findAccountByJid(jid);
    }

    protected void switchToConversation(Contact contact) {
        Conversation conversation = xmppConnectionService
                .findOrCreateConversation(contact.getAccount(),
                        contact.getJid(), false);
        switchToConversation(conversation);
    }

    public static void populateAccountSpinner(Context context, List<String> accounts, Spinner spinner) {
        if (accounts.size() > 0) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.simple_list_item, accounts);
            adapter.setDropDownViewResource(R.layout.simple_list_item);
            spinner.setAdapter(adapter);
            spinner.setEnabled(true);
        } else {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                    R.layout.simple_list_item,
                    Arrays.asList(new String[]{context.getString(R.string.no_accounts)}));
            adapter.setDropDownViewResource(R.layout.simple_list_item);
            spinner.setAdapter(adapter);
            spinner.setEnabled(false);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.start_conversation, menu);
        MenuItem menuCreateContact = menu.findItem(R.id.action_create_contact);
        MenuItem menuCreateConference = menu.findItem(R.id.action_conference);
        MenuItem menuHideOffline = menu.findItem(R.id.action_hide_offline);
        menuHideOffline.setChecked(this.mHideOfflineContacts);
        mMenuSearchView = menu.findItem(R.id.action_search);
        mMenuSearchView.setOnActionExpandListener(mOnActionExpandListener);
        View mSearchView = mMenuSearchView.getActionView();
        mSearchEditText = (EditText) mSearchView
                .findViewById(R.id.search_field);
        mSearchEditText.addTextChangedListener(mSearchTextWatcher);
        mSearchEditText.setOnEditorActionListener(mSearchDone);
        if (getActionBar().getSelectedNavigationIndex() == 0) {
            menuCreateConference.setVisible(false);
        } else {
            menuCreateContact.setVisible(false);
        }
        if (mInitialJid != null) {
            mMenuSearchView.expandActionView();
            mSearchEditText.append(mInitialJid);
            filter(mInitialJid);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_create_contact:
                showCreateContactDialog(null, null);
                return true;
            case R.id.action_join_conference:
                showJoinConferenceDialog(null);
                return true;
            case R.id.action_create_conference:
                showCreateConferenceDialog();
                return true;
            case R.id.action_scan_qr_code:
                new IntentIntegrator(this).initiateScan();
                return true;
            case R.id.action_hide_offline:
                mHideOfflineContacts = !item.isChecked();
                getPreferences().edit().putBoolean("hide_offline", mHideOfflineContacts).commit();
                if (mSearchEditText != null) {
                    filter(mSearchEditText.getText().toString());
                }
                invalidateOptionsMenu();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH && !event.isLongPress()) {
            openSearch();
            return true;
        }
        int c = event.getUnicodeChar();
        if (c > 32) {
            if (mSearchEditText != null && !mSearchEditText.isFocused()) {
                openSearch();
                mSearchEditText.append(Character.toString((char) c));
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private void openSearch() {
        if (mMenuSearchView != null) {
            mMenuSearchView.expandActionView();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if ((requestCode & 0xFFFF) == IntentIntegrator.REQUEST_CODE) {
            IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
            if (scanResult != null && scanResult.getFormatName() != null) {
                String data = scanResult.getContents();
                Invite invite = new Invite(data);
                if (xmppConnectionServiceBound) {
                    invite.invite();
                } else if (invite.getJid() != null) {
                    this.mPendingInvite = invite;
                } else {
                    this.mPendingInvite = null;
                }
            }
        } else if (resultCode == RESULT_OK) {
            if (xmppConnectionServiceBound) {
                this.mPostponedActivityResult = null;
                if (requestCode == REQUEST_CREATE_CONFERENCE) {
                    Account account = extractAccount(intent);
                    final String subject = intent.getStringExtra("subject");
                    List<Jid> jids = new ArrayList<>();
                    if (intent.getBooleanExtra("multiple", false)) {
                        String[] toAdd = intent.getStringArrayExtra("contacts");
                        for (String item : toAdd) {
                            try {
                                jids.add(Jid.fromString(item));
                            } catch (InvalidJidException e) {
                                //ignored
                            }
                        }
                    } else {
                        try {
                            jids.add(Jid.fromString(intent.getStringExtra("contact")));
                        } catch (Exception e) {
                            //ignored
                        }
                    }
                    if (account != null && jids.size() > 0) {
                        xmppConnectionService.createAdhocConference(account, subject, jids, mAdhocConferenceCallback);
                        mToast = Toast.makeText(this, R.string.creating_conference, Toast.LENGTH_LONG);
                        mToast.show();
                    }
                }
            } else {
                this.mPostponedActivityResult = new Pair<>(requestCode, intent);
            }
        }
        super.onActivityResult(requestCode, requestCode, intent);
    }

    private void askForContactsPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                if (mRequestedContactsPermission.compareAndSet(false, true)) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(this);
                        builder.setTitle(R.string.sync_with_contacts);
                        builder.setMessage(R.string.sync_with_contacts_long);
                        builder.setPositiveButton(R.string.next, new OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_SYNC_CONTACTS);
                                }
                            }
                        });
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                            builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface dialog) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_SYNC_CONTACTS);
                                    }
                                }
                            });
                        }
                        builder.create().show();
                    } else {
                        requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 0);
                    }
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (grantResults.length > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_SYNC_CONTACTS && xmppConnectionServiceBound) {
                    xmppConnectionService.loadPhoneContacts();
                }
            }
    }

    @Override
    protected void onBackendConnected() {
        if (mPostponedActivityResult != null) {
            onActivityResult(mPostponedActivityResult.first, RESULT_OK, mPostponedActivityResult.second);
            this.mPostponedActivityResult = null;
        }
        this.mActivatedAccounts.clear();
        for (Account account : xmppConnectionService.getAccounts()) {
            if (account.getStatus() != Account.State.DISABLED) {
                if (Config.DOMAIN_LOCK != null) {
                    this.mActivatedAccounts.add(account.getJid().getLocalpart());
                } else {
                    this.mActivatedAccounts.add(account.getJid().toBareJid().toString());
                }
            }
        }
        final Intent intent = getIntent();
        final ActionBar ab = getActionBar();
        boolean init = intent != null && intent.getBooleanExtra("init", false);
        boolean noConversations = xmppConnectionService.getConversations().size() == 0;
        if ((init || noConversations) && ab != null) {
            ab.setDisplayShowHomeEnabled(false);
            ab.setDisplayHomeAsUpEnabled(false);
            ab.setHomeButtonEnabled(false);
        }
        this.mKnownHosts = xmppConnectionService.getKnownHosts();
        this.mKnownConferenceHosts = xmppConnectionService.getKnownConferenceHosts();
        if (this.mPendingInvite != null) {
            mPendingInvite.invite();
            this.mPendingInvite = null;
        } else if (!handleIntent(getIntent())) {
            if (mSearchEditText != null) {
                filter(mSearchEditText.getText().toString());
            } else {
                filter(null);
            }
        }
        setIntent(null);
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    Invite getInviteJellyBean(NdefRecord record) {
        return new Invite(record.toUri());
    }

    protected boolean handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return false;
        }
        switch (intent.getAction()) {
            case Intent.ACTION_SENDTO:
            case Intent.ACTION_VIEW:
                Uri uri = intent.getData();
                if (uri != null) {
                    Log.d(Config.LOGTAG, "received uri=" + intent.getData());
                    return new Invite(intent.getData()).invite();
                } else {
                    return false;
                }
            case NfcAdapter.ACTION_NDEF_DISCOVERED:
                for (Parcelable message : getIntent().getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)) {
                    if (message instanceof NdefMessage) {
                        Log.d(Config.LOGTAG, "received message=" + message);
                        for (NdefRecord record : ((NdefMessage) message).getRecords()) {
                            switch (record.getTnf()) {
                                case NdefRecord.TNF_WELL_KNOWN:
                                    if (Arrays.equals(record.getType(), NdefRecord.RTD_URI)) {
                                        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                                            return getInviteJellyBean(record).invite();
                                        } else {
                                            byte[] payload = record.getPayload();
                                            if (payload[0] == 0) {
                                                return new Invite(Uri.parse(new String(Arrays.copyOfRange(
                                                        payload, 1, payload.length)))).invite();
                                            }
                                        }
                                    }
                            }
                        }
                    }
                }
        }
        return false;
    }

    private boolean handleJid(Invite invite) {
        Account account = xmppConnectionService.findAccountByJid(invite.getJid());
        if (account != null && !account.isOptionSet(Account.OPTION_DISABLED) && invite.hasFingerprints()) {
            if (xmppConnectionService.verifyFingerprints(account,invite.getFingerprints())) {
                switchToAccount(account);
                finish();
                return true;
            }
        }
        List<Contact> contacts = xmppConnectionService.findContacts(invite.getJid());
        if (invite.isMuc()) {
            Conversation muc = xmppConnectionService.findFirstMuc(invite.getJid());
            if (muc != null) {
                switchToConversation(muc);
                return true;
            } else {
                showJoinConferenceDialog(invite.getJid().toBareJid().toString());
                return false;
            }
        } else if (contacts.size() == 0) {
            showCreateContactDialog(invite.getJid().toString(), invite);
            return false;
        } else if (contacts.size() == 1) {
            Contact contact = contacts.get(0);
            if (invite.hasFingerprints()) {
                xmppConnectionService.verifyFingerprints(contact,invite.getFingerprints());
            }
            switchToConversation(contact);
            return true;
        } else {
            if (mMenuSearchView != null) {
                mMenuSearchView.expandActionView();
                mSearchEditText.setText("");
                mSearchEditText.append(invite.getJid().toString());
                filter(invite.getJid().toString());
            } else {
                mInitialJid = invite.getJid().toString();
            }
            return true;
        }
    }

    protected void filter(String needle) {
        if (xmppConnectionServiceBound) {
            this.filterContacts(needle);
            this.filterConferences(needle);
        }
    }

    protected void filterContacts(String needle) {
        this.contacts.clear();
        for (Account account : xmppConnectionService.getAccounts()) {
            if (account.getStatus() != Account.State.DISABLED) {
                for (Contact contact : account.getRoster().getContacts()) {
                    Presence.Status s = contact.getShownStatus();
                    if (contact.showInRoster() && contact.match(this, needle)
                            && (!this.mHideOfflineContacts
                            || (needle != null && !needle.trim().isEmpty())
                            || s.compareTo(Presence.Status.OFFLINE) < 0)) {
                        this.contacts.add(contact);
                    }
                }
            }
        }
        Collections.sort(this.contacts);
        mContactsAdapter.notifyDataSetChanged();
    }

    protected void filterConferences(String needle) {
        this.conferences.clear();
        for (Account account : xmppConnectionService.getAccounts()) {
            if (account.getStatus() != Account.State.DISABLED) {
                for (Bookmark bookmark : account.getBookmarks()) {
                    if (bookmark.match(this, needle)) {
                        this.conferences.add(bookmark);
                    }
                }
            }
        }
        Collections.sort(this.conferences);
        mConferenceAdapter.notifyDataSetChanged();
    }

    private void onTabChanged() {
        invalidateOptionsMenu();
    }

    @Override
    public void OnUpdateBlocklist(final Status status) {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        if (mSearchEditText != null) {
            filter(mSearchEditText.getText().toString());
        }
    }

    public class ListPagerAdapter extends PagerAdapter {
        FragmentManager fragmentManager;
        MyListFragment[] fragments;

        public ListPagerAdapter(FragmentManager fm) {
            fragmentManager = fm;
            fragments = new MyListFragment[2];
        }

        public void requestFocus(int pos) {
            if (fragments.length > pos) {
                fragments[pos].getListView().requestFocus();
            }
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            assert (0 <= position && position < fragments.length);
            FragmentTransaction trans = fragmentManager.beginTransaction();
            trans.remove(fragments[position]);
            trans.commit();
            fragments[position] = null;
        }

        @Override
        public Fragment instantiateItem(ViewGroup container, int position) {
            Fragment fragment = getItem(position);
            FragmentTransaction trans = fragmentManager.beginTransaction();
            trans.add(container.getId(), fragment, "fragment:" + position);
            trans.commit();
            return fragment;
        }

        @Override
        public int getCount() {
            return fragments.length;
        }

        @Override
        public boolean isViewFromObject(View view, Object fragment) {
            return ((Fragment) fragment).getView() == view;
        }

        public Fragment getItem(int position) {
            assert (0 <= position && position < fragments.length);
            if (fragments[position] == null) {
                final MyListFragment listFragment = new MyListFragment();
                if (position == 1) {
                    listFragment.setListAdapter(mConferenceAdapter);
                    listFragment.setContextMenu(R.menu.conference_context);
                    listFragment.setOnListItemClickListener(new OnItemClickListener() {

                        @Override
                        public void onItemClick(AdapterView<?> arg0, View arg1,
                                                int position, long arg3) {
                            openConversationForBookmark(position);
                        }
                    });
                } else {

                    listFragment.setListAdapter(mContactsAdapter);
                    listFragment.setContextMenu(R.menu.contact_context);
                    listFragment.setOnListItemClickListener(new OnItemClickListener() {

                        @Override
                        public void onItemClick(AdapterView<?> arg0, View arg1,
                                                int position, long arg3) {
                            openConversationForContact(position);
                        }
                    });
                }
                fragments[position] = listFragment;
            }
            return fragments[position];
        }
    }

    public static class MyListFragment extends ListFragment {
        private AdapterView.OnItemClickListener mOnItemClickListener;
        private int mResContextMenu;

        public void setContextMenu(final int res) {
            this.mResContextMenu = res;
        }

        @Override
        public void onListItemClick(final ListView l, final View v, final int position, final long id) {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(l, v, position, id);
            }
        }

        public void setOnListItemClickListener(AdapterView.OnItemClickListener l) {
            this.mOnItemClickListener = l;
        }

        @Override
        public void onViewCreated(final View view, final Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            registerForContextMenu(getListView());
            getListView().setFastScrollEnabled(true);
        }

        @Override
        public void onCreateContextMenu(final ContextMenu menu, final View v,
                                        final ContextMenuInfo menuInfo) {
            super.onCreateContextMenu(menu, v, menuInfo);
            final StartConversationActivity activity = (StartConversationActivity) getActivity();
            activity.getMenuInflater().inflate(mResContextMenu, menu);
            final AdapterView.AdapterContextMenuInfo acmi = (AdapterContextMenuInfo) menuInfo;
            if (mResContextMenu == R.menu.conference_context) {
                activity.conference_context_id = acmi.position;
            } else if (mResContextMenu == R.menu.contact_context) {
                activity.contact_context_id = acmi.position;
                final Contact contact = (Contact) activity.contacts.get(acmi.position);
                final MenuItem blockUnblockItem = menu.findItem(R.id.context_contact_block_unblock);
                final MenuItem showContactDetailsItem = menu.findItem(R.id.context_contact_details);
                if (contact.isSelf()) {
                    showContactDetailsItem.setVisible(false);
                }
                XmppConnection xmpp = contact.getAccount().getXmppConnection();
                if (xmpp != null && xmpp.getFeatures().blocking() && !contact.isSelf()) {
                    if (contact.isBlocked()) {
                        blockUnblockItem.setTitle(R.string.unblock_contact);
                    } else {
                        blockUnblockItem.setTitle(R.string.block_contact);
                    }
                } else {
                    blockUnblockItem.setVisible(false);
                }
            }
        }

        @Override
        public boolean onContextItemSelected(final MenuItem item) {
            StartConversationActivity activity = (StartConversationActivity) getActivity();
            switch (item.getItemId()) {
                case R.id.context_start_conversation:
                    activity.openConversationForContact();
                    break;
                case R.id.context_contact_details:
                    activity.openDetailsForContact();
                    break;
                case R.id.context_contact_block_unblock:
                    activity.toggleContactBlock();
                    break;
                case R.id.context_delete_contact:
                    activity.deleteContact();
                    break;
                case R.id.context_join_conference:
                    activity.openConversationForBookmark();
                    break;
                case R.id.context_delete_conference:
                    activity.deleteConference();
            }
            return true;
        }
    }

    private class Invite extends XmppUri {

        public Invite(final Uri uri) {
            super(uri);
        }

        public Invite(final String uri) {
            super(uri);
        }

        boolean invite() {
            if (getJid() != null) {
                return handleJid(this);
            }
            return false;
        }

        public boolean isMuc() {
            return muc;
        }
    }
}
