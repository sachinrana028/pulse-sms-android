/*
 * Copyright (C) 2016 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.klinker.messenger.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.ContactsContract;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.miguelcatalan.materialsearchview.MaterialSearchView;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import xyz.klinker.messenger.R;
import xyz.klinker.messenger.adapter.ContactAdapter;
import xyz.klinker.messenger.adapter.ConversationListAdapter;
import xyz.klinker.messenger.api.implementation.Account;
import xyz.klinker.messenger.api.implementation.ApiUtils;
import xyz.klinker.messenger.api.implementation.LoginActivity;
import xyz.klinker.messenger.shared.MessengerActivityExtras;
import xyz.klinker.messenger.shared.data.DataSource;
import xyz.klinker.messenger.shared.data.Settings;
import xyz.klinker.messenger.shared.data.model.Conversation;
import xyz.klinker.messenger.fragment.ArchivedConversationListFragment;
import xyz.klinker.messenger.fragment.BlacklistFragment;
import xyz.klinker.messenger.fragment.ConversationListFragment;
import xyz.klinker.messenger.fragment.InviteFriendsFragment;
import xyz.klinker.messenger.fragment.ScheduledMessagesFragment;
import xyz.klinker.messenger.fragment.SearchFragment;
import xyz.klinker.messenger.fragment.bottom_sheet.CustomSnoozeFragment;
import xyz.klinker.messenger.fragment.settings.AboutFragment;
import xyz.klinker.messenger.fragment.settings.HelpAndFeedbackFragment;
import xyz.klinker.messenger.fragment.settings.MyAccountFragment;
import xyz.klinker.messenger.shared.service.ApiDownloadService;
import xyz.klinker.messenger.shared.service.NewMessagesCheckService;
import xyz.klinker.messenger.shared.service.NotificationService;
import xyz.klinker.messenger.shared.service.jobs.SubscriptionExpirationCheckJob;
import xyz.klinker.messenger.shared.util.ActivityUtils;
import xyz.klinker.messenger.shared.util.ColorUtils;
import xyz.klinker.messenger.shared.util.ContactUtils;
import xyz.klinker.messenger.shared.util.CursorUtil;
import xyz.klinker.messenger.shared.util.ImageUtils;
import xyz.klinker.messenger.shared.util.PermissionsUtils;
import xyz.klinker.messenger.shared.util.PhoneNumberUtils;
import xyz.klinker.messenger.shared.util.PromotionUtils;
import xyz.klinker.messenger.shared.util.StringUtils;
import xyz.klinker.messenger.shared.util.TimeUtils;
import xyz.klinker.messenger.shared.util.UnreadBadger;
import xyz.klinker.messenger.shared.view.WhitableToolbar;
import xyz.klinker.messenger.utils.TextAnywhereConversationCardApplier;
import xyz.klinker.messenger.utils.UpdateUtils;
import xyz.klinker.messenger.shared.util.billing.BillingHelper;
import xyz.klinker.messenger.shared.util.listener.BackPressedListener;
import xyz.klinker.messenger.shared.widget.MessengerAppWidgetProvider;

import static xyz.klinker.messenger.shared.MessengerActivityExtras.*;

/**
 * Main entry point to the app. This will serve for setting up the drawer view, finding
 * conversations and displaying things on the screen to get the user started.
 */
public class  MessengerActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener,
        MaterialSearchView.OnQueryTextListener, MaterialSearchView.SearchViewListener {

    private WhitableToolbar toolbar;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private ConversationListFragment conversationListFragment;
    private Fragment otherFragment;
    private SearchFragment searchFragment;
    private FloatingActionButton fab;
    private MaterialSearchView searchView;
    private boolean inSettings = false;
    private boolean startImportOrLoad = false;

    private DataSource dataSource;

    private BroadcastReceiver downloadReceiver;
    private BroadcastReceiver refreshAllReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            recreate();
        }
    };

    public BillingHelper billing;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        new UpdateUtils(this).checkForUpdate();
        new PromotionUtils(this).checkPromotions();

        dataSource = DataSource.INSTANCE;

        setContentView(R.layout.activity_messenger);
        initToolbar();
        initFab();
        configureGlobalColors();
        displayConversations();

        dismissIfFromNotification();

        if (checkInitialStart() && savedInstanceState == null) {
            boolean hasTelephone = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
//            boolean hasSim = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1 ||
//                    DualSimUtils.get(this).getDefaultPhoneNumber() != null;

            boolean hasPhoneFeature = hasTelephone && !getResources().getBoolean(R.bool.is_tablet);
            if (hasPhoneFeature) {
                startActivityForResult(
                        new Intent(this, OnboardingActivity.class),
                        INSTANCE.getREQUEST_ONBOARDING()
                );
            } else {
                // if it isn't a phone, then we want to go straight to the login
                // and skip the onboarding, since they would have done it on their phone
                Intent login = new Intent(this, InitialLoadActivity.class);
                startActivity(login);
                finish();
            }

            startImportOrLoad = true;
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if (conversationListFragment != null) {
            conversationListFragment.dismissSnackbars(this);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requestPermissions();

        new UnreadBadger(this).clearCount();

        if (conversationListFragment != null && conversationListFragment.isExpanded()) {
            NotificationService.CONVERSATION_ID_OPEN = conversationListFragment.getExpandedId();
        }

        ColorUtils.checkBlackBackground(this);
        ActivityUtils.setTaskDescription(this);

        if (!Build.FINGERPRINT.contains("robolectric")) {
            TimeUtils.setupNightTheme(this);
        }

        initDrawer();

        new Handler().postDelayed(() -> {
            if (conversationListFragment != null && !conversationListFragment.isExpanded()) {
                if (!fab.isShown() && otherFragment == null) {
                    fab.show();
                }

                TextAnywhereConversationCardApplier applier = new TextAnywhereConversationCardApplier(conversationListFragment);
                RecyclerView recycler = conversationListFragment.getRecyclerView();
                if (applier.shouldAddCardToList()) {
                    boolean scrollToTop = false;
                    if (recycler.getLayoutManager() instanceof LinearLayoutManager &&
                            ((LinearLayoutManager) recycler.getLayoutManager()).findFirstVisibleItemPosition() == 0) {
                        scrollToTop = true;
                    }

                    applier.addCardToConversationList();

                    if (scrollToTop) {
                        conversationListFragment.getRecyclerView().smoothScrollToPosition(0);
                    }
                }
            }

            snoozeIcon();

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                new Thread(() -> {
                    Cursor c = DataSource.INSTANCE.getUnseenMessages(this);
                    int count = c.getCount();
                    CursorUtil.closeSilent(c);

                    if (count > 1) {
                        // since the notification functionality here is not nearly as good as 7.0,
                        // we will just remove them all, if there is more than one
                        try {
                            NotificationManagerCompat.from(MessengerActivity.this).cancelAll();
                        } catch (IllegalStateException e) { }
                    }
                }).start();
            }
        }, 1000);

        if (getIntent().getBooleanExtra(INSTANCE.getEXTRA_START_MY_ACCOUNT(), false)) {
            NotificationManagerCompat.from(this).cancel(SubscriptionExpirationCheckJob.NOTIFICATION_ID);
            menuItemClicked(R.id.drawer_account);
        }

        handleShortcutIntent(getIntent());
        getIntent().setData(null);

        registerReceiver(refreshAllReceiver,
                new IntentFilter(NewMessagesCheckService.REFRESH_WHOLE_CONVERSATION_LIST));

        if (!startImportOrLoad) {
            new Handler().postDelayed(() ->
                    NewMessagesCheckService.startService(MessengerActivity.this), 3000);
        } else {
            startImportOrLoad = false;
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        boolean handled = handleShortcutIntent(intent);
        long convoId = intent.getLongExtra(MessengerActivityExtras.INSTANCE.getEXTRA_CONVERSATION_ID(), -1L);

        if (!handled && convoId != -1L) {
            getIntent().putExtra(MessengerActivityExtras.INSTANCE.getEXTRA_CONVERSATION_ID(), convoId);
            displayConversations();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        MessengerAppWidgetProvider.refreshWidget(this);

        NotificationService.CONVERSATION_ID_OPEN = 0L;

        try {
            unregisterReceiver(refreshAllReceiver);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        try {
            dataSource.close(this);
        } catch (Exception e) {
            e.printStackTrace();
        }

        super.onDestroy();

        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver);
        }
    }

    private void requestPermissions() {
        if (PermissionsUtils.checkRequestMainPermissions(this)) {
            PermissionsUtils.startMainPermissionRequest(this);
        }

        if (Account.get(this).primary && !PermissionsUtils.isDefaultSmsApp(this)) {
            PermissionsUtils.setDefaultSmsApp(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        try {
            PermissionsUtils.processPermissionRequest(this, requestCode, permissions, grantResults);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);

            if (requestCode == INSTANCE.getREQUEST_CALL_PERMISSION()) {
                callContact();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void showSnackbar(String text, int duration, String actionLabel, View.OnClickListener listener) {
        if (conversationListFragment != null) {
            conversationListFragment.makeSnackbar(text, duration, actionLabel, listener);
        } else {
            Snackbar s = Snackbar.make(findViewById(android.R.id.content), text, duration);

            if (actionLabel != null && listener != null) {
                s.setAction(actionLabel, listener);
            }

            s.show();
        }
    }

    private boolean checkInitialStart() {
        return Settings.get(this).firstStart;
    }

    private void initToolbar() {
        navigationView = (NavigationView) findViewById(R.id.navigation_view);
        toolbar = (WhitableToolbar) findViewById(R.id.toolbar);
        drawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);

        setSupportActionBar(toolbar);
        final ActionBar actionBar = getSupportActionBar();

        if (actionBar != null && drawerLayout != null) {
            actionBar.setHomeAsUpIndicator(R.drawable.ic_menu);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        searchView = (MaterialSearchView) findViewById(R.id.search_view);
        searchView.setVoiceSearch(false);
        searchView.setBackgroundColor(getResources().getColor(R.color.drawerBackground));
        searchView.setOnQueryTextListener(this);
        searchView.setOnSearchViewListener(this);
    }

    private void initDrawer() {
        navigationView.setNavigationItemSelectedListener(this);
        navigationView.postDelayed(() -> {
            Account account = Account.get(getApplicationContext());
            try {
                ((TextView) findViewById(R.id.drawer_header_my_name))
                        .setText(account.myName);
                ((TextView) findViewById(R.id.drawer_header_my_phone_number))
                        .setText(PhoneNumberUtils.format(account.myPhoneNumber));

                if (!ColorUtils.isColorDark(Settings.get(this).mainColorSet.colorDark)) {
                    ((TextView) findViewById(R.id.drawer_header_my_name))
                            .setTextColor(getResources().getColor(R.color.lightToolbarTextColor));
                    ((TextView) findViewById(R.id.drawer_header_my_phone_number))
                            .setTextColor(getResources().getColor(R.color.lightToolbarTextColor));
                }

                // change the text to
                if (Account.get(MessengerActivity.this).accountId == null) {
                    navigationView.getMenu().findItem(R.id.drawer_account).setTitle(R.string.menu_device_texting);
                }
            } catch (NullPointerException e) {
                e.printStackTrace();
            }

            initSnooze();
        }, 300);
    }

    private void initSnooze() {
        ImageButton snooze = (ImageButton) findViewById(R.id.snooze);
        if (snooze == null) {
            return;
        }

        if (!ColorUtils.isColorDark(Settings.get(this).mainColorSet.colorDark)) {
            snooze.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.lightToolbarTextColor)));
        }

        snooze.setOnClickListener(view -> {
            PopupMenu menu = new PopupMenu(MessengerActivity.this, view);
            boolean currentlySnoozed = Settings.get(getApplicationContext()).snooze >
                    System.currentTimeMillis();
            menu.inflate(currentlySnoozed ? R.menu.snooze_off : R.menu.snooze);
            menu.setOnMenuItemClickListener(item -> {
                long snoozeTil;
                switch (item.getItemId()) {
                    case R.id.menu_snooze_off:
                        snoozeTil = System.currentTimeMillis();
                        break;
                    case R.id.menu_snooze_1:
                        snoozeTil = System.currentTimeMillis() + (1000 * 60 * 60);
                        break;
                    case R.id.menu_snooze_2:
                        snoozeTil = System.currentTimeMillis() + (1000 * 60 * 60 * 2);
                        break;
                    case R.id.menu_snooze_4:
                        snoozeTil = System.currentTimeMillis() + (1000 * 60 * 60 * 4);
                        break;
                    case R.id.menu_snooze_8:
                        snoozeTil = System.currentTimeMillis() + (1000 * 60 * 60 * 8);
                        break;
                    case R.id.menu_snooze_24:
                        snoozeTil = System.currentTimeMillis() + (1000 * 60 * 60 * 24);
                        break;
                    case R.id.menu_snooze_72:
                        snoozeTil = System.currentTimeMillis() + (1000 * 60 * 60 * 72);
                        break;
                    case R.id.menu_snooze_custom:
                        CustomSnoozeFragment fragment = new CustomSnoozeFragment();
                        fragment.show(getSupportFragmentManager(), "");
                        // fall through to the default
                    default:
                        snoozeTil = System.currentTimeMillis();
                        break;
                }

                Settings.get(getApplicationContext()).setValue(getApplicationContext(),
                        getString(R.string.pref_snooze), snoozeTil);
                new ApiUtils().updateSnooze(Account.get(getApplicationContext()).accountId,
                        snoozeTil);

                snoozeIcon();

                return true;
            });

            menu.show();
        });
    }

    private void initFab() {
        fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(view ->
                startActivity(new Intent(getApplicationContext(), ComposeActivity.class)));
    }

    private void configureGlobalColors() {
        final Settings settings = Settings.get(this);

        toolbar.setBackgroundColor(settings.mainColorSet.color);
        fab.setBackgroundTintList(ColorStateList.valueOf(settings.mainColorSet.colorAccent));

        int[][] states = new int[][] {
                new int[] {-android.R.attr.state_checked },
                new int[] { android.R.attr.state_checked }
        };

        String baseColor = getResources().getBoolean(R.bool.is_night) ? "FFFFFF" : "000000";
        int[] iconColors = new int[] {
                Color.parseColor("#77" + baseColor),
                settings.mainColorSet.colorAccent
        };

        int[] textColors = new int[] {
                Color.parseColor("#DD" + baseColor),
                settings.mainColorSet.colorAccent
        };

        navigationView.setItemIconTintList(new ColorStateList(states, iconColors));
        navigationView.setItemTextColor(new ColorStateList(states, textColors));
        navigationView.post(() -> {
            ColorUtils.adjustStatusBarColor(settings.mainColorSet.colorDark, MessengerActivity.this);

            View header = navigationView.findViewById(R.id.header);
            if (header != null) {
                header.setBackgroundColor(settings.mainColorSet.colorDark);
            }
        });
    }

    @Override
    @SuppressLint("RestrictedApi")
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }

        List<Fragment> fragments = getSupportFragmentManager().getFragments();

        for (Fragment fragment : fragments) {
            if (fragment instanceof BackPressedListener) {
                if (((BackPressedListener) fragment).onBackPressed()) {
                    return;
                }
            }
        }

        if (searchView.isSearchOpen()) {
            searchView.closeSearch();
            return;
        }

        if (conversationListFragment == null) {
            Fragment messageListFragment = getSupportFragmentManager().findFragmentById(R.id.message_list_container);

            try {
                getSupportFragmentManager().beginTransaction().remove(messageListFragment).commit();
            } catch (Exception e) { }

            displayConversations();
            fab.show();
            if (drawerLayout != null) {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
            }
        } else if (inSettings) {
            clickDefaultDrawerItem();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        closeDrawer();
        
        if (item.isChecked() || ApiDownloadService.IS_RUNNING) {
            return true;
        }

        if (item.isCheckable()) {
            item.setChecked(true);
        }

        if (drawerLayout != null) {
            if (item.getItemId() == R.id.drawer_conversation) {
                setTitle(R.string.app_title);
            } else if (item.isCheckable()) {
                setTitle(StringUtils.titleize(item.getTitle().toString()));
            }
        }

        return menuItemClicked(item.getItemId());
    }

    public boolean menuItemClicked(int id) {
        if (conversationListFragment != null) {
            conversationListFragment.dismissSnackbars(this);
        }

        switch (id) {
            case R.id.drawer_conversation:
                if (conversationListFragment != null && conversationListFragment.archiveSnackbar != null
                        && conversationListFragment.archiveSnackbar.isShown()) {
                    conversationListFragment.archiveSnackbar.dismiss();
                }
                return displayConversations();
            case R.id.drawer_archived:
                if (conversationListFragment != null && conversationListFragment.archiveSnackbar != null
                        && conversationListFragment.archiveSnackbar.isShown()) {
                    conversationListFragment.archiveSnackbar.dismiss();
                }
                return displayArchived();
            case R.id.drawer_schedule:
                return displayScheduledMessages();
            case R.id.drawer_mute_contacts:
                return displayBlacklist();
            case R.id.drawer_invite:
                return displayInviteFriends();
            case R.id.drawer_feature_settings:
                return displayFeatureSettings();
            case R.id.drawer_settings:
                return displaySettings();
            case R.id.drawer_account:
                return displayMyAccount();
            case R.id.drawer_help:
                return displayHelpAndFeedback();
            case R.id.drawer_about:
                return displayAbout();
            case R.id.menu_call:
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                        == PackageManager.PERMISSION_GRANTED) {
                    return callContact();
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, INSTANCE.getREQUEST_CALL_PERMISSION());
                        return false;
                    } else {
                        return callContact();
                    }
                }
            case R.id.menu_view_contact:
            case R.id.drawer_view_contact:
                return viewContact();
            case R.id.menu_view_media:
            case R.id.drawer_view_media:
                return viewMedia();
            case R.id.menu_delete_conversation:
            case R.id.drawer_delete_conversation:
                return deleteConversation();
            case R.id.menu_archive_conversation:
            case R.id.drawer_archive_conversation:
                return archiveConversation();
            case R.id.menu_conversation_information:
            case R.id.drawer_conversation_information:
                return conversationInformation();
            case R.id.menu_conversation_blacklist:
            case R.id.drawer_conversation_blacklist:
                return conversationBlacklist();
            case R.id.menu_conversation_schedule:
            case R.id.drawer_conversation_schedule:
                return conversationSchedule();
            case R.id.menu_contact_settings:
            case R.id.drawer_contact_settings:
                return contactSettings();
            default:
                return true;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!inSettings) {
            getMenuInflater().inflate(R.menu.activity_messenger, menu);

            MenuItem item = menu.findItem(R.id.menu_search);
            item.getIcon().setTintList(ColorStateList.valueOf(toolbar.getTextColor()));
            searchView.setMenuItem(item);
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                openDrawer();
                return true;
            case R.id.menu_search:
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        try {
            getIntent().putExtra(INSTANCE.getEXTRA_CONVERSATION_ID(), conversationListFragment.getExpandedId());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void clickDefaultDrawerItem() {
        clickNavigationItem(R.id.drawer_conversation);
    }

    public void clickNavigationItem(int itemId) {
        onNavigationItemSelected(navigationView.getMenu().findItem(itemId));
    }

    private void openDrawer() {
        if (drawerLayout != null) {
            drawerLayout.openDrawer(GravityCompat.START);
        }
    }

    private void closeDrawer() {
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
    }

    /*****************************************************************
     * conversation list drawer options                             *
     *****************************************************************/

    public boolean displayConversations() {
        NotificationService.CONVERSATION_ID_OPEN = 0L;

        fab.show();
        invalidateOptionsMenu();
        inSettings = false;

        long convoId = getIntent().getLongExtra(INSTANCE.getEXTRA_CONVERSATION_ID(), -1L);
        long messageId = getIntent().getLongExtra(INSTANCE.getEXTRA_MESSAGE_ID(), -1L);

        if (messageId != -1L && convoId != -1L) {
            conversationListFragment = ConversationListFragment.newInstance(convoId, messageId);
            getIntent().putExtra(INSTANCE.getEXTRA_CONVERSATION_ID(), -1L);
            getIntent().putExtra(INSTANCE.getEXTRA_MESSAGE_ID(), -1L);
        } else if (convoId != -1L && convoId != 0) {
            conversationListFragment = ConversationListFragment.newInstance(convoId);
        } else {
            conversationListFragment = ConversationListFragment.newInstance();
        }
        
        otherFragment = null;
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.conversation_list_container, conversationListFragment);

        Fragment messageList = getSupportFragmentManager()
                .findFragmentById(R.id.message_list_container);

        if (messageList != null) {
            transaction.remove(messageList);
        }

        transaction.commit();

        return true;
    }

    private void displayShortcutConversation(long convo) {
        NotificationService.CONVERSATION_ID_OPEN = 0L;

        fab.show();
        invalidateOptionsMenu();
        inSettings = false;

        conversationListFragment = ConversationListFragment.newInstance(convo);

        otherFragment = null;
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.conversation_list_container, conversationListFragment);

        Fragment messageList = getSupportFragmentManager()
                .findFragmentById(R.id.message_list_container);

        if (messageList != null) {
            transaction.remove(messageList);
        }

        transaction.commit();
    }

    private boolean displayArchived() {
        return displayFragmentWithBackStack(new ArchivedConversationListFragment());
    }

    private void displaySearchFragment() {
        otherFragment = null;
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.conversation_list_container, searchFragment)
                .commit();
    }

    private boolean displayScheduledMessages() {
        return displayFragmentWithBackStack(new ScheduledMessagesFragment());
    }

    private boolean displayBlacklist() {
        return displayFragmentWithBackStack(BlacklistFragment.newInstance());
    }

    private boolean displayInviteFriends() {
        return displayFragmentWithBackStack(new InviteFriendsFragment());
    }

    private boolean displaySettings() {
        SettingsActivity.startGlobalSettings(this);
        return true;
    }

    private boolean displayFeatureSettings() {
        SettingsActivity.startFeatureSettings(this);
        return true;
    }

    private boolean displayMyAccount() {
        return displayFragmentWithBackStack(new MyAccountFragment());
    }

    private boolean displayHelpAndFeedback() {
        return displayFragmentWithBackStack(new HelpAndFeedbackFragment());
    }

    private boolean displayAbout() {
        return displayFragmentWithBackStack(new AboutFragment());
    }

    private boolean displayFragmentWithBackStack(final Fragment fragment) {
        if (searchView.isSearchOpen()) {
            searchView.closeSearch();
        }

        fab.hide();
        invalidateOptionsMenu();
        inSettings = true;

        otherFragment = fragment;
        new Handler().postDelayed(() -> {
            try {
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.conversation_list_container, fragment)
                        .commit();
            } catch (Exception e) {
                finish();
                overridePendingTransition(0,0);
                startActivity(new Intent(MessengerActivity.this, MessengerActivity.class));
                overridePendingTransition(0,0);
            }
        }, 200);


        return true;
    }

    /*****************************************************************
     * message list drawer options                                  *
     *****************************************************************/

    public boolean callContact() {
        if (conversationListFragment.isExpanded()) {
            String uri = "tel:" +
                    conversationListFragment.getExpandedItem().conversation.phoneNumbers;
            Intent intent = new Intent(Intent.ACTION_CALL);
            intent.setData(Uri.parse(uri));
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.no_apps_found, Toast.LENGTH_SHORT).show();
            } catch (SecurityException e) {
                Toast.makeText(this, R.string.you_denied_permission, Toast.LENGTH_SHORT).show();
            }

            return true;
        } else if (otherFragment instanceof ArchivedConversationListFragment) {
            ArchivedConversationListFragment frag = (ArchivedConversationListFragment) otherFragment;
            if (frag.isExpanded()) {
                String uri = "tel:" +
                        frag.getExpandedItem().conversation.phoneNumbers;
                Intent intent = new Intent(Intent.ACTION_CALL);
                intent.setData(Uri.parse(uri));
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.no_apps_found, Toast.LENGTH_SHORT).show();
                } catch (SecurityException e) {
                    Toast.makeText(this, R.string.you_denied_permission, Toast.LENGTH_SHORT).show();
                }

                return true;
            }
        }

        return false;
    }

    private boolean viewContact() {
        Conversation conversation = null;

        if (conversationListFragment != null && conversationListFragment.isExpanded()) {
            conversation = conversationListFragment.getExpandedItem().conversation;
        } else if (otherFragment instanceof ArchivedConversationListFragment) {
            ArchivedConversationListFragment frag = (ArchivedConversationListFragment) otherFragment;
            if (frag.isExpanded()) {
                conversation = frag.getExpandedItem().conversation;
            }
        }

        if (conversation != null) {
            String[] names = ContactUtils.findContactNames(conversation.phoneNumbers, this).split(", ");
            String[] numbers = conversation.phoneNumbers.split(", ");
            List<Conversation> conversations = new ArrayList<>();

            for (int i = 0; i < numbers.length; i++) {
                Conversation c = new Conversation();
                c.title = names[i];
                c.phoneNumbers = numbers[i];
                c.imageUri = ContactUtils.findImageUri(numbers[i], this);

                Bitmap image = ImageUtils.getContactImage(c.imageUri, this);
                if (c.imageUri != null && image == null) {
                    c.imageUri = null;
                }

                if (image != null) {
                    image.recycle();
                }

                conversations.add(c);
            }

            ContactAdapter adapter = new ContactAdapter(conversations, (title, phoneNumber, imageUri) -> {
                Intent intent;

                try {
                    intent = new Intent(Intent.ACTION_VIEW);
                    Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI,
                            String.valueOf(ContactUtils.findContactId(phoneNumber,
                                    MessengerActivity.this)));
                    intent.setData(uri);
                } catch (NoSuchElementException e) {
                    e.printStackTrace();
                    try {
                        intent = new Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT);
                        intent.setData(Uri.parse("tel:" + phoneNumber));
                    } catch (ActivityNotFoundException ex) {
                        intent = null;
                    }
                }

                if (intent != null) {
                    startActivity(intent);
                }
            });

            RecyclerView recyclerView = new RecyclerView(this);
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(adapter);

            if (adapter.getItemCount() == 1) {
                Intent intent;

                try {
                    intent = new Intent(Intent.ACTION_VIEW);
                    Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI,
                            String.valueOf(ContactUtils.findContactId(conversation.phoneNumbers,
                                    MessengerActivity.this)));
                    intent.setData(uri);
                } catch (NoSuchElementException e) {
                    e.printStackTrace();
                    try {
                        intent = new Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT);
                        intent.setData(Uri.parse("tel:" + conversation.phoneNumbers));
                    } catch (ActivityNotFoundException ex) {
                        intent = null;
                    }
                }

                try {
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                final Intent editRecipients = new Intent(MessengerActivity.this, ComposeActivity.class);
                editRecipients.setAction(ComposeActivity.ACTION_EDIT_RECIPIENTS);
                editRecipients.putExtra(ComposeActivity.EXTRA_EDIT_RECIPIENTS_TITLE, conversation.title);
                editRecipients.putExtra(ComposeActivity.EXTRA_EDIT_RECIPIENTS_NUMBERS, conversation.phoneNumbers);

                new AlertDialog.Builder(this)
                        .setView(recyclerView)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.edit_recipients, (dialogInterface, i) -> startActivity(editRecipients))
                        .show();
            }

            return true;
        } else {
            return false;
        }
    }

    private boolean viewMedia() {
        if (conversationListFragment.isExpanded()) {
            Intent intent = new Intent(this, MediaGridActivity.class);
            intent.putExtra(MediaGridActivity.EXTRA_CONVERSATION_ID, conversationListFragment.getExpandedId());
            startActivity(intent);

            return true;
        } else {
            return false;
        }
    }

    private boolean isArchiveConvoShowing() {
        return otherFragment != null && otherFragment instanceof ArchivedConversationListFragment &&
                ((ArchivedConversationListFragment) otherFragment).isExpanded();
    }

    private ConversationListFragment getShownConversationList() {
        return isArchiveConvoShowing() ? (ArchivedConversationListFragment) otherFragment : conversationListFragment;
    }

    private boolean deleteConversation() {
        if (conversationListFragment.isExpanded() || isArchiveConvoShowing()) {
            final ConversationListFragment fragment = getShownConversationList();
            final long conversationId = fragment.getExpandedId();
            fragment.onBackPressed();

            new Handler().postDelayed(() -> {
                ConversationListAdapter adapter = fragment.getAdapter();
                int position = adapter.findPositionForConversationId(conversationId);
                if (position != -1) {
                    adapter.deleteItem(position);
                }
            }, 250);

            return true;
        } else {
            return false;
        }
    }

    private boolean archiveConversation() {
        if (conversationListFragment.isExpanded() || isArchiveConvoShowing()) {
            final ConversationListFragment fragment = getShownConversationList();
            final long conversationId = fragment.getExpandedId();
            fragment.onBackPressed();

            new Handler().postDelayed(() -> {
                ConversationListAdapter adapter = fragment.getAdapter();
                int position = adapter.findPositionForConversationId(conversationId);
                if (position != -1) {
                    adapter.archiveItem(position);
                }
            }, 250);

            return true;
        } else {
            return false;
        }
    }

    private boolean conversationInformation() {
        if (conversationListFragment.isExpanded() || isArchiveConvoShowing()) {
            final ConversationListFragment fragment = getShownConversationList();
            Conversation conversation = fragment.getExpandedItem().conversation;
            DataSource source = DataSource.INSTANCE;

            new AlertDialog.Builder(this)
                    .setMessage(source.getConversationDetails(this, conversation))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(R.string.menu_copy_phone_number, (dialogInterface, i) -> {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("phone_number", conversation.phoneNumbers);
                        clipboard.setPrimaryClip(clip);
                    })
                    .show();
            return true;
        } else {
            return false;
        }
    }

    private boolean conversationBlacklist() {
        if (conversationListFragment.isExpanded() || isArchiveConvoShowing()) {
            final ConversationListFragment fragment = getShownConversationList();
            Conversation conversation = fragment.getExpandedItem().conversation;
            fragment.getExpandedItem().itemView.performClick();
            fragment.onSwipeToArchive(conversation);
            clickNavigationItem(R.id.drawer_mute_contacts);
            return displayFragmentWithBackStack(
                    BlacklistFragment.newInstance(conversation.phoneNumbers));
        } else {
            return false;
        }
    }

    private boolean conversationSchedule() {
        if (conversationListFragment.isExpanded() || isArchiveConvoShowing()) {
            final ConversationListFragment fragment = getShownConversationList();
            Conversation conversation = fragment.getExpandedItem().conversation;
            fragment.getExpandedItem().itemView.performClick();
            clickNavigationItem(R.id.drawer_schedule);
            return displayFragmentWithBackStack(
                    ScheduledMessagesFragment.newInstance(conversation.title,
                            conversation.phoneNumbers));
        } else {
            return false;
        }
    }

    private boolean contactSettings() {
        if (conversationListFragment.isExpanded() || isArchiveConvoShowing()) {
            final ConversationListFragment fragment = getShownConversationList();
            long conversationId = fragment.getExpandedId();
            Intent intent = new Intent(this, ContactSettingsActivity.class);
            intent.putExtra(ContactSettingsActivity.EXTRA_CONVERSATION_ID, conversationId);
            startActivity(intent);
            return true;
        } else {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try {
            if (billing != null && billing.handleOnActivityResult(requestCode, resultCode, data)) {
                return;
            }

            Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.message_list_container);
            if (fragment != null) {
                fragment.onActivityResult(requestCode, resultCode, data);
            } else {
                fragment = getSupportFragmentManager().findFragmentById(R.id.conversation_list_container);
                if (fragment != null) {
                    fragment.onActivityResult(requestCode, resultCode, data);
                }
            }

            if (requestCode == INSTANCE.getREQUEST_ONBOARDING()) {
                boolean hasTelephone = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);

                // if it isn't a phone, then we want to force the login.
                // if it is a phone, they can choose to log in when they want to
                Intent login = new Intent(this, InitialLoadActivity.class);
                login.putExtra(LoginActivity.ARG_SKIP_LOGIN, hasTelephone);

                startActivity(login);
                finish();
            }

            super.onActivityResult(requestCode, resultCode, data);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        ensureSearchFragment();
        searchFragment.search(query);
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        if (newText.length() > 0) {
            // display search fragment
            ensureSearchFragment();
            searchFragment.search(newText);
            if (!searchFragment.isAdded()) {
                displaySearchFragment();
            }
        } else {
            // display conversation fragment
            ensureSearchFragment();
            searchFragment.search(null);

            if (!conversationListFragment.isAdded()) {
                displayConversations();
                fab.hide();
            }
        }

        return true;
    }

    @Override
    public void onSearchViewShown() {
        fab.hide();
        ensureSearchFragment();
    }

    @Override
    public void onSearchViewClosed() {
        ensureSearchFragment();

        if (!searchFragment.isSearching()) {
            fab.show();

            if (!conversationListFragment.isAdded()) {
                displayConversations();
            }
        }
    }

    private void ensureSearchFragment() {
        if (searchFragment == null) {
            searchFragment = SearchFragment.newInstance();
        }
    }

    private void dismissIfFromNotification() {
        boolean fromNotification = getIntent().getBooleanExtra(INSTANCE.getEXTRA_FROM_NOTIFICATION(), false);
        long convoId = getIntent().getLongExtra(INSTANCE.getEXTRA_CONVERSATION_ID(), -1L);

        if (fromNotification && convoId != -1) {
            new ApiUtils().dismissNotification(Account.get(this).accountId, Account.get(this).deviceId, convoId);
        }
    }

    public void snoozeIcon() {
        boolean currentlySnoozed = Settings.get(getApplicationContext()).snooze >
                System.currentTimeMillis();
        ImageButton snooze = (ImageButton) findViewById(R.id.snooze);

        try {
            if (currentlySnoozed) {
                snooze.setImageResource(R.drawable.ic_snoozed);
            } else {
                snooze.setImageResource(R.drawable.ic_snooze);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void startDataDownload() {
        new Handler().postDelayed(() -> {
            downloadReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    recreate();
                }
            };

            registerReceiver(downloadReceiver,
                    new IntentFilter(ApiDownloadService.ACTION_DOWNLOAD_FINISHED));

            ApiDownloadService.start(this);

            showSnackbar(getString(R.string.downloading_and_decrypting), Snackbar.LENGTH_LONG, null, null);
        }, 1000);
    }

    private boolean handleShortcutIntent(Intent intent) {
        if (intent.getData() != null && intent.getDataString().contains("https://messenger.klinkerapps.com/")) {
            try {
                if (conversationListFragment != null && conversationListFragment.isExpanded()) {
                    onBackPressed();
                }

                displayShortcutConversation(Long.parseLong(intent.getData().getLastPathSegment()));
                return true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return false;
    }
}
