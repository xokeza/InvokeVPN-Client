package su.xokeza.invokevpn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Json {
    private Json() {}
    static JSONObject obj(Object... values) {
        JSONObject object = new JSONObject();
        for (int index = 0; index < values.length; index += 2) {
            try {
                object.put((String) values[index], values[index + 1]);
            } catch (JSONException error) {
                throw new IllegalArgumentException("Некорректные поля JSON", error);
            }
        }
        return object;
    }

    static JSONArray arr(Object... values) {
        JSONArray array = new JSONArray();
        for (Object value : values) array.put(value);
        return array;
    }
}
