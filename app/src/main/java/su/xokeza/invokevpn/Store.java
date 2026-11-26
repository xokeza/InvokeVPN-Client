package su.xokeza.invokevpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Store {
    private static final String ALIAS = "invokevpn.profiles.v1";
    private static final Object GUARD = new Object();
    final SharedPreferences preferences;

    Store(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("invoke", Context.MODE_PRIVATE);
    }

    String deviceId() {
        synchronized (GUARD) {
            String id = preferences.getString("device", "");
            if (id.isEmpty()) {
                id = UUID.randomUUID().toString();
                preferences.edit().putString("device", id).apply();
            }
            return id;
        }
    }

    List<Subscription> all() {
        synchronized (GUARD) {
            try {
                String encrypted = preferences.getString("profiles", "");
                if (encrypted.isEmpty()) return new ArrayList<>();
                String[] parts = encrypted.split("\\.", 2);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
                byte[] bytes = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
                JSONArray records = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                List<Subscription> profiles = new ArrayList<>();
                for (int i = 0; i < records.length(); i++) profiles.add(Subscription.restore(records.getJSONObject(i)));
                return profiles;
            } catch (Exception error) {
                throw new IllegalStateException("Не удалось открыть хранилище подписок");
            }
        }
    }

    void save(Subscription profile) {
        save(profile, false);
    }

    void save(Subscription profile, boolean existingOnly) {
        synchronized (GUARD) {
            List<Subscription> profiles = all();
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).id.equals(profile.id)) {
                    Subscription previous = profiles.get(i);
                    Node oldNode = previous.nodes.isEmpty() ? null : previous.nodes.get(selectedIndex(previous));
                    int nextIndex = 0;
                    if (oldNode != null) {
                        for (int j = 0; j < profile.nodes.size(); j++) {
                            Node next = profile.nodes.get(j);
                            if (next.link.equals(oldNode.link) || (!oldNode.name.isEmpty() && next.name.equals(oldNode.name))) {
                                nextIndex = j;
                                break;
                            }
                        }
                    }
                    profiles.set(i, profile);
                    write(profiles);
                    preferences.edit().putInt("node:" + profile.id, nextIndex).apply();
                    return;
                }
            }
            if (existingOnly) throw new IllegalStateException("Подписка удалена");
            profiles.add(profile);
            write(profiles);
        }
    }

    Subscription selected() {
        List<Subscription> profiles = all();
        String selected = preferences.getString("selected", "");
        for (Subscription profile : profiles) if (profile.id.equals(selected)) return profile;
        profiles.sort(java.util.Comparator.comparing(profile -> !profile.flag("subscription-pin", false)));
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    int selectedIndex(Subscription profile) {
        return Math.max(0, Math.min(profile.nodes.size() - 1, preferences.getInt("node:" + profile.id, 0)));
    }

    void remove(String id) {
        synchronized (GUARD) {
            List<Subscription> profiles = all();
            profiles.removeIf(profile -> profile.id.equals(id));
            write(profiles);
        }
    }

    private void write(Collection<Subscription> profiles) {
        try {
            JSONArray array = new JSONArray();
            for (Subscription profile : profiles) array.put(profile.stored());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
            String bytes = Base64.encodeToString(cipher.doFinal(array.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            if (!preferences.edit().putString("profiles", iv + "." + bytes).commit()) {
                throw new IllegalStateException("Не удалось сохранить подписки");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось сохранить подписки");
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
