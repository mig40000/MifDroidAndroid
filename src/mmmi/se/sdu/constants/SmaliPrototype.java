/**
 * 
 */
package mmmi.se.sdu.constants;


/**
 * @author abhishektiwari
 *
 */
public class SmaliPrototype {

	//Starting an intent Activity via Start Activity methods
	public String start_Activity_method_1 = ";->startActivity(Landroid/content/Intent;)V";
	public String start_Activity_method_2 = ";->startActivityForResult(Landroid/content/Intent;I)V";
	public String start_Activity_method_3 = ";->startActivity(Landroid/content/Intent;Landroid/os/Bundle;)V";
	public String start_Activity_method_4 = ";->startActivities([Landroid/content/Intent;)V";
	public String start_Activity_method_5 = ";->startActivities([Landroid/content/Intent;Landroid/os/Bundle;)V";
	public String start_Activity_method_6 = ";->startActivityForResult(Landroid/content/Intent;ILandroid/os/Bundle;)V";
	public String start_Activity_method_7 = ";->startActivityFromChild(Landroid/app/Activity;Landroid/content/Intent;ILandroid/os/Bundle;)V";
	public String start_Activity_method_8 = ";->startActivityFromChild(Landroid/app/Activity;Landroid/content/Intent;I)V";
	public String start_Activity_method_9 = ";->startActivityFromFragment(Landroid/app/Fragment;Landroid/content/Intent;I)V";
	public String start_Activity_method_10 = ";->startActivityFromFragment(Landroid/app/Fragment;Landroid/content/Intent;ILandroid/os/Bundle;)V";
	public String start_Activity_method_11 = ";->startActivityIfNeeded(Landroid/content/Intent;I)Z";
	public String start_Activity_method_12 = ";->startActivityIfNeeded(Landroid/content/Intent;ILandroid/os/Bundle;)Z";
	
	//Starting an intent Activity via broadcast methods
	public String broadcast_method_1 = "sendBroadcast(Landroid/content/Intent;)V";
	public String broadcast_method_2 = "sendBroadcast(Landroid/content/Intent;Ljava/lang/String;)V";
	public String broadcast_method_3 = "sendBroadcastAsUser(Landroid/content/Intent;Landroid/os/UserHandle;)V";
	public String broadcast_method_4 = "sendBroadcastAsUser(Landroid/content/Intent;Landroid/os/UserHandle;Ljava/lang/String;)V";
	public String broadcast_method_5 = "sendOrderedBroadcast(Landroid/content/Intent;Ljava/lang/String;)V";
	public String broadcast_method_6 = "sendOrderedBroadcast(Landroid/content/Intent;Ljava/lang/String;Landroid/content/BroadcastReceiver;Landroid/os/Handler;ILjava/lang/String;Landroid/os/Bundle;)V";
	public String broadcast_method_7 = "sendOrderedBroadcastAsUser(Landroid/content/Intent;Landroid/os/UserHandle;Ljava/lang/String;Landroid/content/BroadcastReceiver;Landroid/os/Handler;ILjava/lang/String;Landroid/os/Bundle;)V";
	public String broadcast_method_8 = "sendStickyBroadcast(Landroid/content/Intent;)V";
	public String broadcast_method_9 = "sendStickyBroadcastAsUser(Landroid/content/Intent;Landroid/os/UserHandle;)V";
	public String broadcast_method_10 = "sendStickyOrderedBroadcast(Landroid/content/Intent;Landroid/content/BroadcastReceiver;Landroid/os/Handler;ILjava/lang/String;Landroid/os/Bundle;)V";
	public String broadcast_method_11 = "sendStickyOrderedBroadcastAsUser(Landroid/content/Intent;Landroid/os/UserHandle;Landroid/content/BroadcastReceiver;Landroid/os/Handler;ILjava/lang/String;Landroid/os/Bundle;)V";

	//Starting an intent Activity via Start Service
	public String start_service = "startService(Landroid/content/Intent;)Landroid/content/ComponentName;";
	public String bind_service = "bindService(Landroid/content/Intent;Landroid/content/ServiceConnection;I)Z";
	
	//return methods
	public String service_connected = ".method public onServiceConnected(Landroid/content/ComponentName;Landroid/os/IBinder;)V";
	public String set_result = "setResult(ILandroid/content/Intent;)V";
	
	//register receivers 
	public String register_receiver = "registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;";
	
	
	//Keeping data in an Intent
	public String put_method_1 = "putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;";
	public String put_method_2 = "putExtra(Ljava/lang/String;Landroid/os/Parcelable;)Landroid/content/Intent;";
	public String put_method_3 = "putExtra(Ljava/lang/String;[J)Landroid/content/Intent;";
	public String put_method_4 = "putExtra(Ljava/lang/String;B)Landroid/content/Intent;";
	public String put_method_5 = "putExtra(Ljava/lang/String;[D)Landroid/content/Intent;";
	public String put_method_6 = "putExtra(Ljava/lang/String;Ljava/lang/CharSequence;)Landroid/content/Intent;";
	public String put_method_7 = "putExtra(Ljava/lang/String;[Z)Landroid/content/Intent;";
	public String put_method_8 = "putExtra(Ljava/lang/String;I)Landroid/content/Intent;";
	public String put_method_9 = "putExtra(Ljava/lang/String;[C)Landroid/content/Intent;";
	public String put_method_10 = "putExtra(Ljava/lang/String;[B)Landroid/content/Intent;";
	public String put_method_11 = "putExtra(Ljava/lang/String;[Landroid/os/Parcelable;)Landroid/content/Intent;";
	public String put_method_12 = "putExtra(Ljava/lang/String;Landroid/os/Bundle;)Landroid/content/Intent;";
	public String put_method_13 = "putExtra(Ljava/lang/String;[Ljava/lang/CharSequence;)Landroid/content/Intent;";
	public String put_method_14 = "putExtra(Ljava/lang/String;[F)Landroid/content/Intent;";
	public String put_method_15 = "putExtra(Ljava/lang/String;D)Landroid/content/Intent;";
	public String put_method_16 = "putExtra(Ljava/lang/String;[I)Landroid/content/Intent;";
	public String put_method_17 = "putExtra(Ljava/lang/String;[Ljava/lang/String;)Landroid/content/Intent;";
	public String put_method_18 = "putExtra(Ljava/lang/String;[S)Landroid/content/Intent;";
	public String put_method_19 = "putExtra(Ljava/lang/String;Z)Landroid/content/Intent;";
	public String put_method_20 = "putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;";
	public String put_method_21 = "putExtra(Ljava/lang/String;J)Landroid/content/Intent;";
	public String put_method_22 = "putExtra(Ljava/lang/String;C)Landroid/content/Intent;";
	public String put_method_23 = "putExtra(Ljava/lang/String;Ljava/io/Serializable;)Landroid/content/Intent;";
	public String put_method_24 = "putExtra(Ljava/lang/String;F)Landroid/content/Intent;";
	public String put_method_25 = "putExtra(Ljava/lang/String;S)Landroid/content/Intent;";
	public String put_method_26 = "putExtras(Landroid/content/Intent;)Landroid/content/Intent;";
	public String put_method_27 = "putExtras(Landroid/os/Bundle;)Landroid/content/Intent;";
	public String put_method_28 = "putIntegerArrayListExtra(Ljava/lang/String;Ljava/util/ArrayList;)Landroid/content/Intent;";
	public String put_method_29 = "putParcelableArrayListExtra(Ljava/lang/String;Ljava/util/ArrayList;)Landroid/content/Intent;";
	public String put_method_30 = "putStringArrayListExtra(Ljava/lang/String;Ljava/util/ArrayList;)Landroid/content/Intent;";
	
	
	//Get data from an Intent
	public String get_method_1 = "getString(Ljava/lang/String;)Ljava/lang/String;";
	public String get_method_2 = "getStringExtra(Ljava/lang/String;)Ljava/lang/String;";
	public String get_method_3 = "getBooleanArrayExtra(Ljava/lang/String;)[Z";
	public String get_method_4 = "getBooleanExtra(Ljava/lang/String;Z)Z";
	public String get_method_5 = "getBundleExtra(Ljava/lang/String;)Landroid/os/Bundle;";
	public String get_method_6 = "getByteArrayExtra(Ljava/lang/String;)[B";
	public String get_method_7 = "getByteExtra(Ljava/lang/String;B)B";
	public String get_method_8 = "getCharArrayExtra(Ljava/lang/String;)[C";
	public String get_method_9 = "getCharExtra(Ljava/lang/String;C)C";
	public String get_method_10 = "getCharSequenceArrayExtra(Ljava/lang/String;)[Ljava/lang/CharSequence;";
	public String get_method_11 = "getCharSequenceArrayListExtra(Ljava/lang/String;)Ljava/util/ArrayList;";
	public String get_method_12 = "getCharSequenceExtra(Ljava/lang/String;)Ljava/lang/CharSequence;";
	public String get_method_13 = "getClipData()Landroid/content/ClipData;";
	public String get_method_14 = "getComponent()Landroid/content/ComponentName;";
	public String get_method_15 = "getData()Landroid/net/Uri;";
	public String get_method_16 = "getDataString()Ljava/lang/String;";
	public String get_method_17 = "getExtras()Landroid/os/Bundle;";
	public String get_method_18 = "getFlags()I";
	public String get_method_19 = "getDoubleArrayExtra(Ljava/lang/String;)[D";
	public String get_method_20 = "getDoubleExtra(Ljava/lang/String;D)D";
	public String get_method_21 = "getFloatArrayExtra(Ljava/lang/String;)[F";
	public String get_method_22 = "getFloatExtra(Ljava/lang/String;F)F";
	public String get_method_23 = "getIntArrayExtra(Ljava/lang/String;)[I";
	public String get_method_24 = "getIntExtra(Ljava/lang/String;I)I";
	public String get_method_25 = "getIntegerArrayListExtra(Ljava/lang/String;)Ljava/util/ArrayList;";
	public String get_method_26 = "getIntent(Ljava/lang/String;)Landroid/content/Intent;";
	public String get_method_27 = "getIntentOld(Ljava/lang/String;)Landroid/content/Intent;";
	public String get_method_28 = "getLongArrayExtra(Ljava/lang/String;)[J";
	public String get_method_29 = "getLongExtra(Ljava/lang/String;J)J";
	public String get_method_30 = "getParcelableArrayExtra(Ljava/lang/String;)[Landroid/os/Parcelable;";
	public String get_method_31 = "getParcelableArrayListExtra(Ljava/lang/String;)Ljava/util/ArrayList;";
	public String get_method_32 = "getParcelableExtra(Ljava/lang/String;)Landroid/os/Parcelable;";
	public String get_method_33 = "getSerializableExtra(Ljava/lang/String;)Ljava/io/Serializable;";
	public String get_method_34 = "getShortArrayExtra(Ljava/lang/String;)[S";
	public String get_method_35 = "getShortExtra(Ljava/lang/String;S)S";
	public String get_method_36 = "getSourceBounds()Landroid/graphics/Rect;";
	public String get_method_37 = "getScheme()Ljava/lang/String;";
	public String get_method_38 = "getStringArrayExtra(Ljava/lang/String;)[Ljava/lang/String;";
	public String get_method_39 = "getStringArrayListExtra(Ljava/lang/String;)Ljava/util/ArrayList;";
	public String get_method_40 = "getSelector()Landroid/content/Intent;";
	public String get_method_41 = "getType()Ljava/lang/String;";
	public String get_method_42 = "getAction()Ljava/lang/String;";
	
	//Initializing an Intent
	public String intent_init_1 = "Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V";
	public String intent_init_2 = "Landroid/content/Intent;-><init>(Ljava/lang/String;)V";
	public String intent_init_3 = "Landroid/content/Intent;-><init>()V";
	public String intent_init_4 = "Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V";
	public String intent_init_5 = "Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;Landroid/content/Context;Ljava/lang/Class;)V";
	
	//Customizing intents
	public String setActionIntent = "Landroid/content/Intent;->setAction(Ljava/lang/String;)Landroid/content/Intent";
	public String setComponentIntent = "Landroid/content/Intent;->setComponent(Landroid/content/ComponentName;)Landroid/content/Intent;";
	public String setClassNameIntent = "Landroid/content/Intent;->setClassName(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;";
	public String initComponent = "Landroid/content/ComponentName;-><init>(Ljava/lang/String;Ljava/lang/String;)V";
	public String initComponent_1 = "Landroid/content/ComponentName;-><init>(Landroid/content/Context;Ljava/lang/String;)V";
	public String setType = "Landroid/content/Intent;->setType(Ljava/lang/String;)Landroid/content/Intent;";
	public String setData = "Landroid/content/Intent;->setData(Landroid/net/Uri;)Landroid/content/Intent;";
	
	public boolean isImplicitIntentConstructor(String statement){
		boolean isImplicitIntentConstructor = false;	
		isImplicitIntentConstructor = isImplicitIntentConstructor || statement.contains(intent_init_2);
		isImplicitIntentConstructor = isImplicitIntentConstructor || statement.contains(intent_init_3);
		isImplicitIntentConstructor = isImplicitIntentConstructor || statement.contains(intent_init_4);	
		return isImplicitIntentConstructor;
	}
	
	//Pending intent creation
	private static String getActivityStub = "Landroid/app/PendingIntent;->getActivity";
	private static String getActivitiesStub = "Landroid/app/PendingIntent;->getActivities";
	private static String getBroadcastStub = "Landroid/app/PendingIntent;->getBroadcast";
	private static String getServiceStub = "Landroid/app/PendingIntent;->getService";
		
	public static boolean isPendingIntentCreation(String statement){
		boolean isPendingIntentCreation = false;	
		isPendingIntentCreation = isPendingIntentCreation || statement.contains(getActivityStub);
		isPendingIntentCreation = isPendingIntentCreation || statement.contains(getActivitiesStub);
		isPendingIntentCreation = isPendingIntentCreation || statement.contains(getBroadcastStub);
		isPendingIntentCreation = isPendingIntentCreation || statement.contains(getServiceStub);		
		return isPendingIntentCreation;
	}
	
	//Functions that transform implicit intents to explicit intents
	private static String setPackageStub = "Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent";
	
	public boolean isExplicitTransformation(String statement){
		//TODO Here are multiple function stubs missing
		boolean isExplicitTransformation = false;
		isExplicitTransformation = isExplicitTransformation || statement.contains(setPackageStub);
		return isExplicitTransformation;
	}
	
	
	
}
