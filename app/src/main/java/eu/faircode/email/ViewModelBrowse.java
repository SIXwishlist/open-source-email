package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.util.Log;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.util.FolderClosedIOException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.mail.FetchProfile;
import javax.mail.Folder;
import javax.mail.FolderClosedException;
import javax.mail.Message;
import javax.mail.MessageRemovedException;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.UIDFolder;
import javax.mail.search.BodyTerm;
import javax.mail.search.FromStringTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.RecipientStringTerm;
import javax.mail.search.SubjectTerm;

import androidx.lifecycle.ViewModel;

public class ViewModelBrowse extends ViewModel {
    private State currentState = null;

    private class State {
        private Context context;
        private long fid;
        private String search;
        private int pageSize;

        int local = 0;
        List<Long> messages = null;
        IMAPStore istore = null;
        IMAPFolder ifolder = null;
        Message[] imessages = null;

        int index = -1;
    }

    void set(Context context, long folder, String search, int pageSize) {
        currentState = new State();
        currentState.context = context;
        currentState.fid = folder;
        currentState.search = search;
        currentState.pageSize = pageSize;
        currentState.index = -1;
    }

    void load() throws MessagingException, IOException {
        State state = currentState;
        if (state == null)
            return;

        DB db = DB.getInstance(state.context);
        EntityFolder folder = db.folder().getFolder(state.fid);

        if (state.search != null)
            try {
                db.beginTransaction();

                if (state.messages == null)
                    state.messages = db.message().getMessageByFolder(state.fid);

                int matched = 0;
                for (int i = state.local; i < state.messages.size() && matched < state.pageSize; i++) {
                    state.local = i + 1;

                    boolean match = false;
                    String find = state.search.toLowerCase();
                    EntityMessage message = db.message().getMessage(state.messages.get(i));
                    String body = (message.content ? message.read(state.context) : null);

                    if (message.from != null)
                        for (int j = 0; j < message.from.length && !match; j++)
                            match = message.from[j].toString().toLowerCase().contains(find);

                    if (message.to != null)
                        for (int j = 0; j < message.to.length && !match; j++)
                            match = message.to[j].toString().toLowerCase().contains(find);

                    if (message.subject != null && !match)
                        match = message.subject.toLowerCase().contains(find);

                    if (!match && message.content)
                        match = body.toLowerCase().contains(find);

                    if (match) {
                        EntityMessage exists = db.message().getMessageByUid(state.fid, message.uid, state.search != null);
                        if (exists == null) {
                            matched++;
                            message.id = null;
                            message.ui_found = true;
                            message.id = db.message().insertMessage(message);
                            if (message.content)
                                message.write(state.context, body);
                        }
                    }
                }

                db.setTransactionSuccessful();

                if (++matched >= state.pageSize)
                    return;
            } finally {
                db.endTransaction();
            }

        if (state.imessages == null) {
            if (folder.account == null) // outbox
                return;

            EntityAccount account = db.account().getAccount(folder.account);

            Properties props = MessageHelper.getSessionProperties(account.auth_type, account.insecure);
            props.setProperty("mail.imap.throwsearchexception", "true");
            Session isession = Session.getInstance(props, null);

            Log.i(Helper.TAG, "Boundary connecting account=" + account.name);
            state.istore = (IMAPStore) isession.getStore(account.starttls ? "imap" : "imaps");
            Helper.connect(state.context, state.istore, account);

            Log.i(Helper.TAG, "Boundary opening folder=" + folder.name);
            state.ifolder = (IMAPFolder) state.istore.getFolder(folder.name);
            state.ifolder.open(Folder.READ_WRITE);

            Log.i(Helper.TAG, "Boundary searching=" + state.search);
            if (state.search == null)
                state.imessages = state.ifolder.getMessages();
            else
                state.imessages = state.ifolder.search(
                        new OrTerm(
                                new OrTerm(
                                        new FromStringTerm(state.search),
                                        new RecipientStringTerm(Message.RecipientType.TO, state.search)
                                ),
                                new OrTerm(
                                        new SubjectTerm(state.search),
                                        new BodyTerm(state.search)
                                )
                        )
                );
            Log.i(Helper.TAG, "Boundary found messages=" + state.imessages.length);

            state.index = state.imessages.length - 1;
        }

        int count = 0;
        while (state.index >= 0 && count < state.pageSize && currentState != null) {
            Log.i(Helper.TAG, "Boundary index=" + state.index);
            int from = Math.max(0, state.index - (state.pageSize - count) + 1);
            Message[] isub = Arrays.copyOfRange(state.imessages, from, state.index + 1);
            state.index -= (state.pageSize - count);

            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.FLAGS);
            fp.add(FetchProfile.Item.CONTENT_INFO); // body structure
            fp.add(UIDFolder.FetchProfileItem.UID);
            fp.add(IMAPFolder.FetchProfileItem.HEADERS);
            fp.add(FetchProfile.Item.SIZE);
            fp.add(IMAPFolder.FetchProfileItem.INTERNALDATE);
            state.ifolder.fetch(isub, fp);

            try {
                db.beginTransaction();

                for (int j = isub.length - 1; j >= 0; j--)
                    try {
                        long uid = state.ifolder.getUID(isub[j]);
                        Log.i(Helper.TAG, "Boundary sync uid=" + uid);
                        EntityMessage message = db.message().getMessageByUid(state.fid, uid, state.search != null);
                        if (message == null) {
                            ServiceSynchronize.synchronizeMessage(
                                    state.context, folder, state.ifolder, (IMAPMessage) isub[j], state.search != null);
                            count++;
                        }
                    } catch (MessageRemovedException ex) {
                        Log.w(Helper.TAG, "Boundary " + ex + "\n" + Log.getStackTraceString(ex));
                    } catch (FolderClosedException ex) {
                        throw ex;
                    } catch (FolderClosedIOException ex) {
                        throw ex;
                    } catch (Throwable ex) {
                        Log.e(Helper.TAG, "Boundary " + ex + "\n" + Log.getStackTraceString(ex));
                    } finally {
                        ((IMAPMessage) isub[j]).invalidateHeaders();
                    }

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        Log.i(Helper.TAG, "Boundary done");
    }

    void clear() {
        State state = currentState;
        if (state == null)
            return;
        currentState = null;

        Log.i(Helper.TAG, "Boundary clear");
        try {
            if (state.istore != null)
                state.istore.close();
        } catch (Throwable ex) {
            Log.e(Helper.TAG, "Boundary " + ex + "\n" + Log.getStackTraceString(ex));
        } finally {
            state.context = null;
            state.messages = null;
            state.istore = null;
            state.ifolder = null;
            state.imessages = null;
        }
    }
}
