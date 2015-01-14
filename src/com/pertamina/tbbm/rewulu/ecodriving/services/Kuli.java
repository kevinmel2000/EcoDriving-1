package com.pertamina.tbbm.rewulu.ecodriving.services;

import java.util.ArrayList;
import java.util.List;

import com.pertamina.tbbm.rewulu.ecodriving.clients.LogsClient;
import com.pertamina.tbbm.rewulu.ecodriving.clients.TripClient;
import com.pertamina.tbbm.rewulu.ecodriving.clients.LogsClient.ResponseLogs;
import com.pertamina.tbbm.rewulu.ecodriving.clients.TripClient.ResponseData;
import com.pertamina.tbbm.rewulu.ecodriving.databases.DataLogAdapter;
import com.pertamina.tbbm.rewulu.ecodriving.databases.TripDataAdapter;
import com.pertamina.tbbm.rewulu.ecodriving.databases.sps.UserDataSP;
import com.pertamina.tbbm.rewulu.ecodriving.locations.GeocoderEngine;
import com.pertamina.tbbm.rewulu.ecodriving.pojos.DataLog;
import com.pertamina.tbbm.rewulu.ecodriving.pojos.ResponseLog;
import com.pertamina.tbbm.rewulu.ecodriving.pojos.Tripdata;
import com.pertamina.tbbm.rewulu.ecodriving.utils.Api;
import com.pertamina.tbbm.rewulu.ecodriving.utils.Loggers;
import com.pertamina.tbbm.rewulu.ecodriving.utils.Utils;

import android.app.IntentService;
import android.content.Intent;
import android.os.AsyncTask;

public class Kuli extends IntentService {

	public Kuli() {
		super("Kuli");
		// TODO Auto-generated constructor stub
	}

	private List<Tripdata> trips;

	@Override
	protected void onHandleIntent(Intent intent) {
		// TODO Auto-generated method stub
		Loggers.i("Kuli", "START");
		trips = TripDataAdapter.readAllTrip(getApplicationContext());
		List<Tripdata> inCompletes = new ArrayList<Tripdata>();
		List<Tripdata> running = new ArrayList<Tripdata>();
		if (trips == null) {
			Loggers.i("Kuli", "trips == null");
			return;
		}
		if (trips.isEmpty()) {
			Loggers.i("Kuli", "trips.isEmpty()");
			return;
		}
		for (Tripdata trip : trips) {
			if (trip.isIncomplete()) {
				inCompletes.add(trip);
			} else if (trip.isRunning()) {
				running.add(trip);
			}
		}
		if (!inCompletes.isEmpty()
				&& !builder.getStatus().equals(AsyncTask.Status.RUNNING)) {
			Loggers.i("Kuli", "Trying to build trip - inCompletes.size() "
					+ inCompletes.size());
			builder = new Builders(inCompletes);
			builder.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
		}
		if (!running.isEmpty()
				&& !deletes.getStatus().equals(AsyncTask.Status.RUNNING)) {
			Loggers.i("Kuli", "Trying to delete trip - running.size() "
					+ running.size());
			deletes = new Delete(running);
			deletes.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
		}
	}

	@Override
	public void onDestroy() {
		// TODO Auto-generated method stub
		super.onDestroy();
		Loggers.i("Kuli", "onDestroy");
		try {
			builder.cancel(true);
			deletes.cancel(true);
		} catch (Exception e) {
			// TODO: handle exception
		}
	}

	private Builders builder = new Builders();
	private Delete deletes = new Delete();

	private class Builders extends AsyncTask<Tripdata, Boolean, Boolean> {
		private List<Tripdata> trips;

		public Builders() {
			// TODO Auto-generated constructor stub
		}

		public Builders(List<Tripdata> trips) {
			// TODO Auto-generated constructor stub
			this.trips = trips;
		}

		@Override
		protected Boolean doInBackground(Tripdata... params) {
			// TODO Auto-generated method stub
			if (trips == null)
				return null;
			Loggers.getInstance("Kuli");
			Loggers.i("Builders()", "Trying to build trip size " + trips.size());
			for (Tripdata trip : trips) {
				trip.setUser(UserDataSP.get(getApplicationContext()));
				List<DataLog> logs = DataLogAdapter.readAllLogByTrip(
						getApplicationContext(), trip);
				if (logs.isEmpty()) {
					trip.setStatus(Tripdata.STATUS_RUNNING);
					TripDataAdapter.updateTrip(getApplicationContext(), trip);
					return null;
				}
				List<DataLog> unLogged = new ArrayList<>();
				trip.Log("Kuli - Builders");
				Loggers.i("", "all log size " + logs.size());
				for (DataLog log : logs)
					if (log.getRow_id() < 0) {
						unLogged.add(log);
					}
				boolean net = Utils.isInternetAvailable();
				Loggers.i("Kuli", "net " + net);
				while (net) {
					Loggers.v("Kuli", "===================================== ");
					if (!trip.isAddressStartSet()) {
						Loggers.i("Kuli", "getAddress Start");
						GeocoderEngine geocoder = new GeocoderEngine(
								getApplicationContext());
						String addrss = geocoder.getAddress(logs.get(0)
								.getLatitude(), logs.get(0).getLongitude());
						if (addrss != null) {
							Loggers.i("Kuli", "result getAddress Start"
									+ addrss);
							trip.setAddrss_start(addrss);
						}
					}
					if (!trip.isAddressEndSet()) {
						Loggers.i("Kuli", "getAddress End");
						GeocoderEngine geocoder = new GeocoderEngine(
								getApplicationContext());
						String addrss = geocoder.getAddress(
								logs.get(logs.size() - 1).getLatitude(), logs
										.get(logs.size() - 1).getLongitude());
						if (addrss != null) {
							Loggers.i("Kuli", "result getAddress End" + addrss);
							trip.setAddrss_end(addrss);
						}
					}
					if (trip.isAddressEndSet() && trip.isAddressStartSet()) {
						TripDataAdapter.updateTrip(getApplicationContext(),
								trip);
						List<DataLog> temp = new ArrayList<>();
						Loggers.i("Kuli",
								"trying to logs size " + unLogged.size());
						while (!unLogged.isEmpty() && net) {
							Loggers.i("Kuli",
									"trying to logs size " + unLogged.size());
							if (temp.size() < 30)
								for (int w = 0; w < unLogged.size(); w++) {
									temp.add(unLogged.get(w));
									unLogged.remove(w);
									w -= 1;
									if (temp.size() > 30)
										break;
								}
							Loggers.i(
									"Kuli",
									"trying to logs size " + temp.size()
											+ " from unLogged size "
											+ unLogged.size());
							ResponseLogs res = LogsClient.logging(temp);
							if (res != null)
								if (res.logs != null) {
									Loggers.i("Kuli", "responselogs size "
											+ res.logs.size());
									for (ResponseLog rs : res.logs) {
										if (!rs.error) {
											for (int w = 0; w < temp.size(); w++) {
												if (rs.id == temp.get(w)
														.getId()) {
													temp.get(w).setRow_id(
															rs.row_id);
													DataLogAdapter
															.updateLog(
																	getApplicationContext(),
																	temp.get(w));
													temp.remove(w);
													w -= 1;
													break;
												}
											}
										}
									}
								}
							try {
								Thread.sleep(10000);
							} catch (Exception e) {
								// TODO: handle exception
							}
						}
						ResponseData res = TripClient.update(trip);
						if (res != null) {
							if (!res.error)
								break;
							Loggers.w("TripClient.update(trip)", res.message);
						}
					}
					try {
						Thread.sleep(5000);
					} catch (Exception e) {
						// TODO: handle exception
					}
				}
			}
			return null;
		}

		@Override
		protected void onPostExecute(Boolean result) {
			// TODO Auto-generated method stub
			super.onPostExecute(result);
		}
	}

	private class Delete extends AsyncTask<Tripdata, Boolean, Boolean> {

		private List<Tripdata> trips;

		public Delete() {
			// TODO Auto-generated constructor stub
		}

		public Delete(List<Tripdata> trips) {
			// TODO Auto-generated constructor stub
			this.trips = trips;
		}

		@Override
		protected Boolean doInBackground(Tripdata... params) {
			// TODO Auto-generated method stub
			if (trips == null)
				return null;
			for (Tripdata trip : trips) {
				trip.setUser(UserDataSP.get(getApplicationContext()));
				if (trip.getRow_id() < 0) {
					TripDataAdapter.deleteById(getApplicationContext(), trip);
					break;
				}
				boolean net = Utils.isInternetAvailable();
				while (net) {
					ResponseData res = TripClient.delete(trip);
					if (res != null) {
						TripDataAdapter.deleteById(getApplicationContext(),
								trip);
						break;
					}
					try {
						Thread.sleep(5000);
					} catch (Exception e) {
						// TODO: handle exception
					}
				}
			}
			return null;
		}

		@Override
		protected void onPostExecute(Boolean result) {
			// TODO Auto-generated method stub
			super.onPostExecute(result);
		}
	}
}
