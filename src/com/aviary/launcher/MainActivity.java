package com.aviary.launcher;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.ImageColumns;
import android.speech.RecognizerIntent;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.aviary.android.feather.FeatherActivity;
import com.aviary.android.feather.headless.AviaryExecutionException;
import com.aviary.android.feather.headless.AviaryInitializationException;
import com.aviary.android.feather.headless.filters.NativeFilterProxy;
import com.aviary.android.feather.headless.media.ExifInterfaceWrapper;
import com.aviary.android.feather.headless.moa.MoaHD;
import com.aviary.android.feather.headless.utils.IOUtils;
import com.aviary.android.feather.headless.utils.MegaPixels;
import com.aviary.android.feather.headless.utils.StringUtils;
import com.aviary.android.feather.library.Constants;
import com.aviary.android.feather.library.filters.FilterLoaderFactory;
import com.aviary.android.feather.library.providers.FeatherContentProvider;
import com.aviary.android.feather.library.providers.FeatherContentProvider.ActionsDbColumns.Action;
import com.aviary.android.feather.library.utils.DecodeUtils;
import com.aviary.android.feather.library.utils.ImageLoader.ImageSizes;
import com.aviary.android.feather.library.utils.SystemUtils;

public class MainActivity extends Activity {
	
	/**
	 * ========== READ ME FIRST ===========
	 * In order to use the Aviary SDK correctly you must first
	 * get your own API-KEY from http://aviary.com/android.
	 * Then copy the generated API-KEY into a file called "aviary-credentials.txt"
	 * inside your assets folder.
	 * Without that file your Activity would be unable to launch FeatherActivity.
	 */
	

	private static final int ACTION_REQUEST_GALLERY = 99;
	private static final int ACTION_REQUEST_FEATHER = 100;
	private static final int EXTERNAL_STORAGE_UNAVAILABLE = 1;
	
	/*
	protected static final int RESULT_SPEECH = 23;
	private static final boolean listen = false;
	public Intent speechIntent;
	*/
	public static final String LOG_TAG = "aviary-launcher";

	/** apikey is required http://developers.aviary.com/ */
	private static final String API_KEY = "xxxxx";

	/** Folder name on the sdcard where the images will be saved **/
	private static final String FOLDER_NAME = "aviary";

	Button mGalleryButton;
	Button mEditButton;
	ImageView mImage;
	View mImageContainer;
	String mOutputFilePath;
	Uri mImageUri;
	int imageWidth, imageHeight;
	private File mGalleryFolder;

	/** session id for the hi-res post processing */
	private String mSessionId;

	@Override
	protected void onCreate( Bundle savedInstanceState ) {
		Log.i( LOG_TAG, "onCreate" );
		super.onCreate( savedInstanceState );
		setContentView( R.layout.main );

		DisplayMetrics metrics = getResources().getDisplayMetrics();
		imageWidth = (int) ( (float) metrics.widthPixels / 1.5 );
		imageHeight = (int) ( (float) metrics.heightPixels / 1.5 );

		mGalleryButton.setOnClickListener( new View.OnClickListener() {

			@Override
			public void onClick( View v ) {
				pickFromGallery();
			}
		} );

		mEditButton.setOnClickListener( new View.OnClickListener() {

			@Override
			public void onClick( View v ) {
				if ( mImageUri != null ) {
					Log.w( LOG_TAG, "intent: feather ");
					startFeather( mImageUri );
					
					/*
					Log.i(LOG_TAG, "intent: audio");
					speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
					speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, "en-US");
					
					// start to listen
					startAudio(speechIntent); 
					*/
					
				}
			}
		} );

		mImageContainer.setOnClickListener( new View.OnClickListener() {

			@Override
			public void onClick( View v ) {
				Uri uri = pickRandomImage();
				if ( uri != null ) {
					Log.d( LOG_TAG, "image uri: " + uri );
					loadAsync( uri );
				}
			}
		} );

		mImageContainer.setLongClickable( true );
		mImageContainer.setOnLongClickListener( new OnLongClickListener() {

			@Override
			public boolean onLongClick( View v ) {
				if ( mImageUri != null ) {
					Log.d( LOG_TAG, "onLongClick: " + v );
					openContextMenu( v );
					return true;
				}
				return false;
			}
		} );

		Toast.makeText( this, "launcher: " + getLibraryVersion() + ", sdk: " + FeatherActivity.SDK_VERSION, Toast.LENGTH_SHORT )
				.show();

		mGalleryFolder = createFolders();
		registerForContextMenu( mImageContainer );
	}

	@Override
	protected void onResume() {
		Log.i( LOG_TAG, "onResume" );
		super.onResume();

		if ( getIntent() != null ) {
			handleIntent( getIntent() );
			setIntent( new Intent() );
		}
	}

	@Override
	public void onCreateContextMenu( ContextMenu menu, View v, ContextMenuInfo menuInfo ) {
		super.onCreateContextMenu( menu, v, menuInfo );
		menu.setHeaderTitle( "Menu" );
		menu.add( 0, 0, 0, "Details" );
	}

	@Override
	public boolean onContextItemSelected( MenuItem item ) {

		final int order = item.getOrder();
		switch ( order ) {
			case 0:
				showCurrentImageDetails();
				return true;
		}

		return super.onContextItemSelected( item );
	}

	/**
	 * Handle the incoming {@link Intent}
	 */
	private void handleIntent( Intent intent ) {

		String action = intent.getAction();

		if ( null != action ) {

			if ( Intent.ACTION_SEND.equals( action ) ) {

				Bundle extras = intent.getExtras();
				if ( extras != null && extras.containsKey( Intent.EXTRA_STREAM ) ) {
					Uri uri = (Uri) extras.get( Intent.EXTRA_STREAM );
					loadAsync( uri );
				}
			} else if ( Intent.ACTION_VIEW.equals( action ) ) {
				Uri data = intent.getData();
				Log.d( LOG_TAG, "data: " + data );
				loadAsync( data );
			}
		}
	}

	/**
	 * Load the incoming Image
	 * 
	 * @param uri
	 */
	private void loadAsync( final Uri uri ) {
		Log.i( LOG_TAG, "loadAsync: " + uri );

		Drawable toRecycle = mImage.getDrawable();
		if ( toRecycle != null && toRecycle instanceof BitmapDrawable ) {
			if ( ( (BitmapDrawable) mImage.getDrawable() ).getBitmap() != null )
				( (BitmapDrawable) mImage.getDrawable() ).getBitmap().recycle();
		}
		mImage.setImageDrawable( null );
		mImageUri = null;

		DownloadAsync task = new DownloadAsync();
		task.execute( uri );
	}

	@Override
	protected void onDestroy() {
		Log.i( LOG_TAG, "onDestroy" );
		super.onDestroy();
		mOutputFilePath = null;
	}

	/**
	 * Load the image details and pass the result
	 * to the {@link ImageInfoActivity} activity
	 */
	private void showCurrentImageDetails() {
		if ( null != mImageUri ) {
			ImageInfo info;
			try {
				info = new ImageInfo( this, mImageUri );
			} catch ( IOException e ) {
				e.printStackTrace();
				return;
			}

			if ( null != info ) {
				Intent intent = new Intent( this, ImageInfoActivity.class );
				intent.putExtra( "image-info", info );
				startActivity( intent );
			}
		}
	}

	/**
	 * Delete a file without throwing any exception
	 * 
	 * @param path
	 * @return
	 */
	private boolean deleteFileNoThrow( String path ) {
		File file;
		try {
			file = new File( path );
		} catch ( NullPointerException e ) {
			return false;
		}

		if ( file.exists() ) {
			return file.delete();
		}
		return false;
	}

	@Override
	public void onContentChanged() {
		super.onContentChanged();

		mGalleryButton = (Button) findViewById( R.id.button1 );
		mEditButton = (Button) findViewById( R.id.button2 );
		mImage = ( (ImageView) findViewById( R.id.image ) );
		mImageContainer = findViewById( R.id.image_container );
	}

	@Override
	public boolean onCreateOptionsMenu( Menu menu ) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate( R.menu.main_menu, menu );
		return true;
	}

	@Override
	public boolean onOptionsItemSelected( MenuItem item ) {

		Intent intent;

		final int id = item.getItemId();

		if ( id == R.id.view_documentation ) {
			intent = new Intent( Intent.ACTION_VIEW );
			intent.setData( Uri.parse( "http://www.aviary.com/android-documentation" ) );
			startActivity( intent );
		} else if ( id == R.id.get_sdk ) {

			intent = new Intent( Intent.ACTION_VIEW );
			intent.setData( Uri.parse( "https://github.com/AviaryInc/Mobile-Feather-SDK-for-Android" ) );
			startActivity( intent );
		}
		return super.onOptionsItemSelected( item );
	}

	@Override
	protected Dialog onCreateDialog( int id ) {
		Dialog dialog = null;
		switch ( id ) {
		// external sdcard is not mounted!
			case EXTERNAL_STORAGE_UNAVAILABLE:
				dialog = new AlertDialog.Builder( this ).setTitle( R.string.external_storage_na_title )
						.setMessage( R.string.external_storage_na_message ).create();
				break;
		}
		return dialog;
	}

	@Override
	/**
	 * This method is called when feather has completed ( ie. user clicked on "done" or just exit the activity without saving ). <br />
	 * If user clicked the "done" button you'll receive RESULT_OK as resultCode, RESULT_CANCELED otherwise.
	 * 
	 * @param requestCode
	 * 	- it is the code passed with startActivityForResult
	 * @param resultCode
	 * 	- result code of the activity launched ( it can be RESULT_OK or RESULT_CANCELED )
	 * @param data
	 * 	- the result data
	 */
	public void onActivityResult( int requestCode, int resultCode, Intent data ) {
		if ( resultCode == RESULT_OK ) {
			switch ( requestCode ) {
				case ACTION_REQUEST_GALLERY:
					Log.w( LOG_TAG, "gallery");
					// user chose an image from the gallery
					loadAsync( data.getData() );
					break;

				case ACTION_REQUEST_FEATHER:
					Log.w( LOG_TAG, "feather");
					boolean changed = true;
					
					if( null != data ) {
						Bundle extra = data.getExtras();
						if( null != extra ) {
							// image was changed by the user?
							changed = extra.getBoolean( Constants.EXTRA_OUT_BITMAP_CHANGED );
						}
					}
					
					if( !changed ) {
						Log.w( LOG_TAG, "User did not modify the image, but just clicked on 'Done' button" );
					}
					
					// send a notification to the media scanner
					updateMedia( mOutputFilePath );
					
					// update the preview with the result
					loadAsync( data.getData() );
					onSaveCompleted( mOutputFilePath );
					mOutputFilePath = null;
					break;
					/*
				// case audio input
				case RESULT_SPEECH:
					Log.w( LOG_TAG, "speech ok ");
					 if (null != data) {

						ArrayList<String> text = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
						Log.i( LOG_TAG, "speech: " + text.get(0));
					}
					startAudio(speechIntent);
					break;
					*/
			}
		} else if ( resultCode == RESULT_CANCELED ) {
			switch ( requestCode ) {
				case ACTION_REQUEST_FEATHER:

					// feather was cancelled without saving.
					// we need to delete the entire session
					if ( null != mSessionId ) deleteSession( mSessionId );

					// delete the result file, if exists
					if ( mOutputFilePath != null ) {
						deleteFileNoThrow( mOutputFilePath );
						mOutputFilePath = null;
					}
					break;
				/*
				case RESULT_SPEECH:
					Log.w( LOG_TAG, "speech nok ");
					startAudio(speechIntent);
					break;
					*/
			}
		}
	}

	/**
	 * lo-res process completed, ask the user if wants to process also the hi-res image
	 * 
	 * @param filepath
	 *           lo-res file name ( in case we want to delete it )
	 */
	private void onSaveCompleted( final String filepath ) {

		if ( mSessionId != null ) {

			OnClickListener yesListener = new OnClickListener() {

				@Override
				public void onClick( DialogInterface dialog, int which ) {
					if ( null != mSessionId ) {
						processHD( mSessionId );
					}
					mSessionId = null;
				}
			};

			OnClickListener noListener = new OnClickListener() {

				@Override
				public void onClick( DialogInterface dialog, int which ) {

					if ( null != mSessionId ) {
						deleteSession( mSessionId );
					}

					if ( !isFinishing() ) {
						dialog.dismiss();
					}
					mSessionId = null;
				}
			};

			Dialog dialog = new AlertDialog.Builder( this ).setTitle( "HiRes" )
					.setMessage( "A low-resolution image was created. Do you want to save the hi-res image too?" )
					.setPositiveButton( android.R.string.yes, yesListener ).setNegativeButton( android.R.string.no, noListener )
					.setCancelable( false ).create();

			dialog.show();
		}
	}

	/**
	 * Given an Uri load the bitmap into the current ImageView and resize it to fit the image container size
	 * 
	 * @param uri
	 */
	@SuppressWarnings("deprecation")
	private boolean setImageURI( final Uri uri, final Bitmap bitmap ) {

		Log.d( LOG_TAG, "image size: " + bitmap.getWidth() + "x" + bitmap.getHeight() );
		mImage.setImageBitmap( bitmap );
		mImage.setBackgroundDrawable( null );

		mEditButton.setEnabled( true );
		mImageUri = uri;
		return true;
	}

	/**
	 * We need to notify the MediaScanner when a new file is created. 
	 * In this way all the gallery applications will be notified too.
	 * 
	 * @param file
	 */
	private void updateMedia( String filepath ) {
		Log.i( LOG_TAG, "updateMedia: " + filepath );
		MediaScannerConnection.scanFile( getApplicationContext(), new String[] { filepath }, null, null );
	}

	/**
	 * Pick a random image from the user gallery
	 * 
	 * @return
	 */
	@SuppressWarnings("unused")
	private Uri pickRandomImage() {
		Cursor c = getContentResolver().query( Images.Media.EXTERNAL_CONTENT_URI, new String[] { ImageColumns._ID, ImageColumns.DATA },
				ImageColumns.SIZE + ">?", new String[] { "90000" }, null );
		Uri uri = null;

		if ( c != null ) {
			int total = c.getCount();
			int position = (int) ( Math.random() * total );
			Log.d( LOG_TAG, "pickRandomImage. total images: " + total + ", position: " + position );
			if ( total > 0 ) {
				if ( c.moveToPosition( position ) ) {
					String data = c.getString( c.getColumnIndex( Images.ImageColumns.DATA ) );
					long id = c.getLong( c.getColumnIndex( Images.ImageColumns._ID ) );
					
					// you can pass to the Aviary-SDK an uri with a "content://" scheme
					// or an abolute file path like "file:///mnt/..." or just "/mnt/..."
					
					// using the "content:/" style uri
					// uri = Uri.withAppendedPath( Images.Media.EXTERNAL_CONTENT_URI, String.valueOf( id ) );
					
					// using the file scheme uri, passing the real path
					uri = Uri.parse( data );
					
					Log.d( LOG_TAG, uri.toString() );
				}
			}
			c.close();
		}
		return uri;
	}

	/**
	 * Return the current application version string
	 * 
	 * @return
	 */
	private String getLibraryVersion() {
		String result = "";

		try {
			PackageManager manager = getPackageManager();
			PackageInfo info = manager.getPackageInfo( getPackageName(), 0 );
			result = info.versionName;
		} catch ( Exception e ) {}

		return result;
	}

	/**
	 * Return a new image file. Name is based on the current time. Parent folder will be the one created with createFolders
	 * 
	 * @return
	 * @see #createFolders()
	 */
	private File getNextFileName() {
		if ( mGalleryFolder != null ) {
			if ( mGalleryFolder.exists() ) {
				File file = new File( mGalleryFolder, "aviary_" + System.currentTimeMillis() + ".jpg" );
				return file;
			}
		}
		return null;
	}
	/*
	private void startAudio(Intent intent) {
		Log.i(LOG_TAG, "audio started");
	
		try {
			startActivityForResult(intent, RESULT_SPEECH);
		} catch (ActivityNotFoundException a) {
			Toast t = Toast.makeText(getApplicationContext(),
					"Ops! Your device doesn't support Speech to Text",
					Toast.LENGTH_SHORT);
			t.show();
		}
	}
	*/
	
	/**
	 * Once you've chosen an image you can start the feather activity
	 * 
	 * @param uri
	 */
	@SuppressWarnings("deprecation")
	private void startFeather( Uri uri ) {

		Log.d( LOG_TAG, "uri: " + uri );

		// first check the external storage availability
		if ( !isExternalStorageAvilable() ) {
			showDialog( EXTERNAL_STORAGE_UNAVAILABLE );
			return;
		}

		// create a temporary file where to store the resulting image
		File file = getNextFileName();
		
		
		if ( null != file ) {
			mOutputFilePath = file.getAbsolutePath();
		} else {
			new AlertDialog.Builder( this ).setTitle( android.R.string.dialog_alert_title ).setMessage( "Failed to create a new File" )
					.show();
			return;
		}
		
		// Create the intent needed to start feather
		Intent newIntent = new Intent( this, FeatherActivity.class );

		// === INPUT IMAGE URI ( MANDATORY )===
		// Set the source image uri
		newIntent.setData( uri );

		// === OUTPUT ====
		// Optional 
		// Pass the uri of the destination image file.
		// This will be the same uri you will receive in the onActivityResult
		newIntent.putExtra( Constants.EXTRA_OUTPUT, Uri.parse( "file://" + mOutputFilePath ) );

		// === OUTPUT FORMAT ===
		// Optional
		// Format of the destination image
		newIntent.putExtra( Constants.EXTRA_OUTPUT_FORMAT, Bitmap.CompressFormat.JPEG.name() );

		// === OUTPUT QUALITY ===
		// Optional
		// Output format quality (jpeg only)
		newIntent.putExtra( Constants.EXTRA_OUTPUT_QUALITY, 90 );

		// === ENABLE/DISABLE IAP FOR EFFECTS ===
		// Optional
		// If you want to disable the external effects
		// newIntent.putExtra( Constants.EXTRA_EFFECTS_ENABLE_EXTERNAL_PACKS, false );
		
		// === ENABLE/DISABLE IAP FOR FRAMES===
		// Optional
		// If you want to disable the external borders.
		// Note that this will remove the frames tool.
		// newIntent.putExtra( Constants.EXTRA_FRAMES_ENABLE_EXTERNAL_PACKS, false );		

		// == ENABLE/DISABLE IAP FOR STICKERS ===
		// Optional
		// If you want to disable the external stickers. In this case you must have a folder called "stickers" in your assets folder
		// containing a list of .png files, which will be your default stickers
		// newIntent.putExtra( Constants.EXTRA_STICKERS_ENABLE_EXTERNAL_PACKS, false );
		
		// enable fast rendering preview
		// newIntent.putExtra( Constants.EXTRA_EFFECTS_ENABLE_FAST_PREVIEW, true );

		// == TOOLS LIST ===
		// Optional
		// You can force feather to display only some tools ( see FilterLoaderFactory#Filters )
		// you can omit this if you just want to display the default tools

		
		 newIntent.putExtra( "tools-list", new String[] { 
		 //FilterLoaderFactory.Filters.ENHANCE.name(),
		 //FilterLoaderFactory.Filters.EFFECTS.name(), 
		 //FilterLoaderFactory.Filters.BORDERS.name(), 
		 //FilterLoaderFactory.Filters.STICKERS.name(),
		 //FilterLoaderFactory.Filters.CROP.name(), 
		 FilterLoaderFactory.Filters.TILT_SHIFT.name(),
		 FilterLoaderFactory.Filters.ADJUST.name(), 
		 FilterLoaderFactory.Filters.BRIGHTNESS.name(), 
		 //FilterLoaderFactory.Filters.CONTRAST.name(), 
		 //FilterLoaderFactory.Filters.SATURATION.name(), 
		 //FilterLoaderFactory.Filters.COLORTEMP.name(),
		 //FilterLoaderFactory.Filters.SHARPNESS.name(), 
		
		 //FilterLoaderFactory.Filters.COLOR_SPLASH.name(),
		 //FilterLoaderFactory.Filters.DRAWING.name(), 
		 //FilterLoaderFactory.Filters.TEXT.name(), 
		 //FilterLoaderFactory.Filters.RED_EYE.name(), 
		 //FilterLoaderFactory.Filters.WHITEN.name(), 
		 //FilterLoaderFactory.Filters.BLEMISH.name(),
		 //FilterLoaderFactory.Filters.MEME.name(),
		 } );


		// === EXIT ALERT ===
		// Optional
		// Uou want to hide the exit alert dialog shown when back is pressed
		// without saving image first
		// newIntent.putExtra( Constants.EXTRA_HIDE_EXIT_UNSAVE_CONFIRMATION, true );

		// === VIBRATION ===
		// Optional
		// Some aviary tools use the device vibration in order to give a better experience
		// to the final user. But if you want to disable this feature, just pass
		// any value with the key "tools-vibration-disabled" in the calling intent.
		// This option has been added to version 2.1.5 of the Aviary SDK
		// newIntent.putExtra( Constants.EXTRA_TOOLS_DISABLE_VIBRATION, true );

		// === MAX SIZE ===
		// Optional
		// you can pass the maximum allowed image size (for the preview), otherwise feather will determine
		// the max size based on the device informations.
		// This will not affect the hi-res image size.
		// Here we're passing the current display size as max image size because after
		// the execution of Aviary we're saving the HI-RES image so we don't need a big
		// image for the preview
		final DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics( metrics );
		int max_size = Math.max( metrics.widthPixels, metrics.heightPixels );
		max_size = (int) ( (float) max_size / 1.2f );
		newIntent.putExtra( Constants.EXTRA_MAX_IMAGE_SIZE, max_size );

		// === HI-RES ===
		// You need to generate a new session id key to pass to Aviary feather
		// this is the key used to operate with the hi-res image ( and must be unique for every new instance of Feather )
		// The session-id key must be 64 char length.
		// In your "onActivityResult" method, if the resultCode is RESULT_OK, the returned
		// bundle data will also contain the "session" key/value you are passing here.
		mSessionId = StringUtils.getSha256( System.currentTimeMillis() + API_KEY );
		Log.d( LOG_TAG, "session: " + mSessionId + ", size: " + mSessionId.length() );
		newIntent.putExtra( Constants.EXTRA_OUTPUT_HIRES_SESSION_ID, mSessionId );

		// === NO CHANGES ==
		// With this extra param you can tell to FeatherActivity how to manage
		// the press on the Done button even when no real changes were made to
		// the image.
		// If the value is true then the image will be always saved, a RESULT_OK
		// will be returned to your onActivityResult and the result Bundle will 
		// contain an extra value "EXTRA_OUT_BITMAP_CHANGED" indicating if the
		// image was changed during the session.
		// If "false" is passed then a RESULT_CANCEL will be sent when an user will
		// hit the 'Done' button without any modifications ( also the EXTRA_OUT_BITMAP_CHANGED
		// extra will be sent back to the onActivityResult.
		// By default this value is true ( even if you omit it )
		newIntent.putExtra( Constants.EXTRA_IN_SAVE_ON_NO_CHANGES, true );
		
		// ..and start feather
		startActivityForResult( newIntent, ACTION_REQUEST_FEATHER );
	}

	/**
	 * Check the external storage status
	 * 
	 * @return
	 */
	private boolean isExternalStorageAvilable() {
		String state = Environment.getExternalStorageState();
		if ( Environment.MEDIA_MOUNTED.equals( state ) ) {
			return true;
		}
		return false;
	}

	/**
	 * Start the activity to pick an image from the user gallery
	 */
	private void pickFromGallery() {
		Intent intent = new Intent( Intent.ACTION_GET_CONTENT );
		intent.setType( "image/*" );

		Intent chooser = Intent.createChooser( intent, "Choose a Picture" );
		startActivityForResult( chooser, ACTION_REQUEST_GALLERY );
	}

	/**
	 * Try to create the required folder on the sdcard where images will be saved to.
	 * 
	 * @return
	 */
	private File createFolders() {

		File baseDir;

		if ( android.os.Build.VERSION.SDK_INT < 8 ) {
			baseDir = Environment.getExternalStorageDirectory();
		} else {
			baseDir = Environment.getExternalStoragePublicDirectory( Environment.DIRECTORY_PICTURES );
		}

		if ( baseDir == null ) return Environment.getExternalStorageDirectory();

		Log.d( LOG_TAG, "Pictures folder: " + baseDir.getAbsolutePath() );
		File aviaryFolder = new File( baseDir, FOLDER_NAME );

		if ( aviaryFolder.exists() ) return aviaryFolder;
		if ( aviaryFolder.mkdirs() ) return aviaryFolder;

		return Environment.getExternalStorageDirectory();
	}

	/**
	 * Start the hi-res image processing.
	 * 
	 */
	private void processHD( final String session_name ) {

		Log.i( LOG_TAG, "processHD: " + session_name );

		// get a new file for the hi-res file
		File destination = getNextFileName();

		try {
			if ( destination == null || !destination.createNewFile() ) {
				Log.e( LOG_TAG, "Failed to create a new file" );
				return;
			}
		} catch ( IOException e ) {
			Log.e( LOG_TAG, e.getMessage() );
			Toast.makeText( this, e.getLocalizedMessage(), Toast.LENGTH_SHORT ).show();
			return;
		}

		String error = null;

		// Now we need to fetch the session information from the content provider
		FeatherContentProvider.SessionsDbColumns.Session session = null;

		Uri sessionUri = FeatherContentProvider.SessionsDbColumns.getContentUri( this, session_name );

		// this query will return a cursor with the informations about the given session
		Cursor cursor = getContentResolver().query( sessionUri, null, null, null, null );

		if ( null != cursor ) {
			session = FeatherContentProvider.SessionsDbColumns.Session.Create( cursor );
			cursor.close();
		}

		if ( null != session ) {
			// Print out the session informations
			Log.d( LOG_TAG, "session.id: " + session.id ); // session _id
			Log.d( LOG_TAG, "session.name: " + session.session ); // session name
			Log.d( LOG_TAG, "session.ctime: " + session.ctime ); // creation time
			Log.d( LOG_TAG, "session.file_name: " + session.file_name ); // original file, it is the same you passed in the
																								// startActivityForResult Intent

			// Now, based on the session information we need to retrieve
			// the list of actions to apply to the hi-res image
			Uri actionsUri = FeatherContentProvider.ActionsDbColumns.getContentUri( this, session.session );

			// this query will return the list of actions performed on the original file, during the FeatherActivity session.
			// Now you can apply each action to the hi-res image to replicate the same result on the bigger image
			cursor = getContentResolver().query( actionsUri, null, null, null, null );

			if ( null != cursor ) {
				// If the cursor is valid we will start a new asynctask process to query the cursor
				// and apply all the actions in a queue
				HDAsyncTask task = new HDAsyncTask( Uri.parse( session.file_name ), destination.getAbsolutePath(), session_name );
				task.execute( cursor );
			} else {
				error = "Failed to retrieve the list of actions!";
			}
		} else {
			error = "Failed to retrieve the session informations";
		}

		if ( null != error ) {
			Toast.makeText( this, error, Toast.LENGTH_LONG ).show();
		}
	}

	/**
	 * Delete the session and all it's actions. We do not need it anymore.<br />
	 * Note that this is optional. All old sessions are automatically removed in Feather.
	 * 
	 * @param session_id
	 */
	private void deleteSession( final String session_id ) {
		Uri uri = FeatherContentProvider.SessionsDbColumns.getContentUri( this, session_id );
		getContentResolver().delete( uri, null, null );
	}

	/**
	 * AsyncTask for Hi-Res image processing
	 * 
	 * @author alessandro
	 * 
	 */
	private class HDAsyncTask extends AsyncTask<Cursor, Integer, String> {

		Uri uri_;
		String dstPath_;
		ProgressDialog progress_;
		String session_;
		ExifInterfaceWrapper exif_;

		/**
		 * Initialize the HiRes async task
		 * 
		 * @param source
		 *           - source image file
		 * @param destination
		 *           - destination image file
		 * @param session_id
		 *           - the session id used to retrieve the list of actions
		 */
		public HDAsyncTask( Uri source, String destination, String session_id ) {
			uri_ = source;
			dstPath_ = destination;
			session_ = session_id;
		}

		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			progress_ = new ProgressDialog( MainActivity.this );
			progress_.setIndeterminate( true );
			progress_.setTitle( "Processing Hi-res image" );
			progress_.setMessage( "Loading image..." );
			progress_.setProgressStyle( ProgressDialog.STYLE_SPINNER );
			progress_.setCancelable( false );
			progress_.show();
		}

		@Override
		protected void onProgressUpdate( Integer... values ) {
			super.onProgressUpdate( values );

			final int index = values[0];
			final int total = values[1];
			String message = "";

			if ( index == -1 )
				message = "Saving image...";
			else
				message = "Applying action " + ( index + 1 ) + " of " + ( total );

			progress_.setMessage( message );

			Log.d( LOG_TAG, index + "/" + total + ", message: " + message );
		}

		@Override
		protected String doInBackground( Cursor... params ) {
			Cursor cursor = params[0];

			if ( null != cursor ) {

				// IMPORTANT NOTE:
				// If in your manifest you're using a different process for the FeatherActivity Activity
				// then you *MUST* call this method before using any of the MoaHD methods, otherwise
				// you will receive a java exception
				try {
					NativeFilterProxy.init( getBaseContext(), API_KEY );
				} catch ( AviaryInitializationException e ) {
					return e.getMessage();
				}
				
				
				// Initialize the class to perform HD operations
				MoaHD moa = new MoaHD();
				
				// Premium partners only: 
				// 	by default the maximum image size for hi-res is set to 13Mp ( is fixed to 3mp for the free version of the sdk )
				moa.setMaxMegaPixels( MegaPixels.Mp30 );
				
				boolean loaded;
				try {
					loaded = loadImage( moa );
				} catch ( AviaryExecutionException e ) {
					return e.getMessage();
				}

				// if image is loaded
				if ( loaded ) {

					final int total_actions = cursor.getCount();
					
					Log.d( LOG_TAG, "total actions: " + total_actions );

					if ( cursor.moveToFirst() ) {

						// get the total number of actions in the queue
						// we're adding also the 'load' and the 'save' action to the total count

						// now for each action in the given cursor, apply the action to
						// the MoaHD instance
						do {
							// send a progress notification to the progressbar dialog
							publishProgress( cursor.getPosition(), total_actions );

							// load the action from the current cursor
							Action action = Action.Create( cursor );
							if ( null != action ) {
								Log.d( LOG_TAG, "executing: " + action.id + "(" + action.session_id + " on " + action.ctime + ") = "
										+ action.getActions() );
								
								// apply a list of actions to the current image
								moa.applyActions( action.getActions() );
							} else {
								Log.e( LOG_TAG, "Woa, something went wrong! Invalid action returned" );
							}

							// move the cursor to next position
						} while ( cursor.moveToNext() );
					}

					// at the end of all the operations we need to save
					// the modified image to a new file
					publishProgress( -1, -1 );
					
					try {
						moa.save( dstPath_ );
					} catch ( AviaryExecutionException e ) {
						return e.getMessage();
					} finally {
						moa.dispose();
					}

					// ok, now we can save the source image EXIF tags
					// to the new image
					if ( null != exif_ ) {
						saveExif( exif_, dstPath_ );
					}

				} else {
					return "Failed to load the image";
				}
				
				cursor.close();

				// and unload the current bitmap. Note that you *MUST* call this method to free the memory allocated with the load
				// method
				
				if( moa.isLoaded() ) {
					try {
						moa.unload();
					} catch ( AviaryExecutionException e ) {}
				}
				
				if( !moa.isDisposed() ) {
					// finally dispose the moahd instance
					moa.dispose();
				}
			}

			return null;
		}

		/**
		 * Save the Exif tags to the new image
		 * 
		 * @param originalExif
		 * @param filename
		 */
		private void saveExif( ExifInterfaceWrapper originalExif, String filename ) {
			// ok, now we can save back the EXIF tags
			// to the new file
			ExifInterfaceWrapper newExif = null;
			try {
				newExif = new ExifInterfaceWrapper( dstPath_ );
			} catch ( IOException e ) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if ( null != newExif && null != originalExif ) {
				originalExif.copyTo( newExif );
				// this should be changed because the editor already rotate the image
				newExif.setAttribute( ExifInterfaceWrapper.TAG_ORIENTATION, "0" );
				// let's update the software tag too
				newExif.setAttribute( ExifInterfaceWrapper.TAG_SOFTWARE, "Aviary " + FeatherActivity.SDK_VERSION );
				// ...and the modification date
				newExif.setAttribute( ExifInterfaceWrapper.TAG_DATETIME, ExifInterfaceWrapper.formatDate( new Date() ) );
				try {
					newExif.saveAttributes();
				} catch ( IOException e ) {
					e.printStackTrace();
				}
			}
		}

		@Override
		protected void onPostExecute( String errorString ) {
			super.onPostExecute( errorString );

			if ( progress_.getWindow() != null ) {
				progress_.dismiss();
			}

			// in case we had an error...
			if ( null != errorString ) {
				Toast.makeText( MainActivity.this, "There was an error: " + errorString, Toast.LENGTH_SHORT ).show();
				return;
			}

			// finally notify the MediaScanner of the new generated file
			updateMedia( dstPath_ );

			// now ask the user if he want to see the saved image
			new AlertDialog.Builder( MainActivity.this ).setTitle( "File saved" )
					.setMessage( "File saved in " + dstPath_ + ". Do you want to see the HD file?" )
					.setPositiveButton( android.R.string.yes, new OnClickListener() {

						@Override
						public void onClick( DialogInterface dialog, int which ) {

							Intent intent = new Intent( Intent.ACTION_VIEW );

							String filepath = dstPath_;
							if ( !filepath.startsWith( "file:" ) ) {
								filepath = "file://" + filepath;
							}
							intent.setDataAndType( Uri.parse( filepath ), "image/jpeg" );
							startActivity( intent );

						}
					} ).setNegativeButton( android.R.string.no, null ).show();

			// we don't need the session anymore, now we can delete it.
			Log.d( LOG_TAG, "delete session: " + session_ );
			deleteSession( session_ );
		}

		private boolean loadImage( MoaHD moa ) throws AviaryExecutionException {
			final String srcPath = IOUtils.getRealFilePath( MainActivity.this, uri_ );
			if ( srcPath != null ) {

				// Let's try to load the EXIF tags from
				// the source image
				try {
					exif_ = new ExifInterfaceWrapper( srcPath );
				} catch ( IOException e ) {
					e.printStackTrace();
				}
				moa.load( srcPath );
				return true;
				
			} else {

				if ( SystemUtils.isHoneyComb() ) {
					InputStream stream = null;
					try {
						stream = getContentResolver().openInputStream( uri_ );
					} catch ( IOException e ) {
						// stream is not valid
						e.printStackTrace();
						return false;
					}
					
					moa.load( stream );
					return true;
					
				} else {
					ParcelFileDescriptor fd = null;
					try {
						fd = getContentResolver().openFileDescriptor( uri_, "r" );
					} catch ( FileNotFoundException e ) {
						// file not found
						e.printStackTrace();
						return false;
					}
					
					moa.load( fd.getFileDescriptor() );
					return true;
				}
			}
		}
	}

	class DownloadAsync extends AsyncTask<Uri, Void, Bitmap> implements OnCancelListener {

		ProgressDialog mProgress;
		private Uri mUri;

		@Override
		protected void onPreExecute() {
			super.onPreExecute();

			mProgress = new ProgressDialog( MainActivity.this );
			mProgress.setIndeterminate( true );
			mProgress.setCancelable( true );
			mProgress.setMessage( "Loading image..." );
			mProgress.setOnCancelListener( this );
			mProgress.show();
		}

		@Override
		protected Bitmap doInBackground( Uri... params ) {
			mUri = params[0];
			Bitmap bitmap = null;

			while ( mImageContainer.getWidth() < 1 ) {
				try {
					Thread.sleep( 1 );
				} catch ( InterruptedException e ) {
					e.printStackTrace();
				}
			}

			final int w = mImageContainer.getWidth();
			Log.d( LOG_TAG, "width: " + w );
			ImageSizes sizes = new ImageSizes();
			bitmap = DecodeUtils.decode( MainActivity.this, mUri, imageWidth, imageHeight, sizes );
			return bitmap;
		}

		@Override
		protected void onPostExecute( Bitmap result ) {
			super.onPostExecute( result );

			if ( mProgress.getWindow() != null ) {
				mProgress.dismiss();
			}

			if ( result != null ) {
				setImageURI( mUri, result );
			} else {
				Toast.makeText( MainActivity.this, "Failed to load image " + mUri, Toast.LENGTH_SHORT ).show();
			}
		}

		@Override
		public void onCancel( DialogInterface dialog ) {
			Log.i( LOG_TAG, "onProgressCancel" );
			this.cancel( true );
		}

		@Override
		protected void onCancelled() {
			super.onCancelled();
			Log.i( LOG_TAG, "onCancelled" );
		}

	}
}
