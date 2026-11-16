package com.tok.interceptor;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public class AppPickerActivity extends Activity {

    public static final String EXTRA_PACKAGE = "selected_package";
    public static final String EXTRA_UID     = "selected_uid";

    private ListView listView;
    private List<ApplicationInfo> apps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Pick Target App");

        listView = new ListView(this);
        setContentView(listView);

        loadApps();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        apps = pm.getInstalledApplications(0);
        // filter — only user apps
        List<ApplicationInfo> userApps = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                userApps.add(ai);
            }
        }
        apps = userApps;
        apps.sort((a, b) ->
            pm.getApplicationLabel(a).toString()
              .compareToIgnoreCase(pm.getApplicationLabel(b).toString()));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1);
        for (ApplicationInfo ai : apps) {
            adapter.add(pm.getApplicationLabel(ai).toString() + "\n" + ai.packageName);
        }

        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            ApplicationInfo selected = apps.get(position);
            Intent result = new Intent();
            result.putExtra(EXTRA_PACKAGE, selected.packageName);
            result.putExtra(EXTRA_UID, selected.uid);
            setResult(RESULT_OK, result);
            finish();
        });
    }
}
