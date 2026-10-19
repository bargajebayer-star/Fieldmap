// FieldMap: receives places from the app and appends them to this Google Sheet.
// Photos are saved to a Drive folder and linked in the sheet.

const SECRET = 'change-this-secret';        // must match the key typed in the app
const SHEET_NAME = 'Locations';
const PHOTO_FOLDER = 'FieldMap Photos';

const HEADERS = ['ID', 'Received', 'Date', 'Employee', 'Name', 'Village', 'Category',
                 'Latitude', 'Longitude', 'Accuracy_m', 'Note', 'Photo', 'Map'];

function doGet() {
  return out_({ ok: true, info: 'FieldMap script is running' });
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.key !== SECRET) return out_({ ok: false, error: 'गुप्त शब्द (key) चुकीचा आहे' });
    if (d.action === 'ping') return out_({ ok: true });

    lock.waitLock(30000);
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const sh = ss.getSheetByName(SHEET_NAME) || ss.insertSheet(SHEET_NAME);
    if (sh.getLastRow() === 0) {
      sh.appendRow(HEADERS);
      sh.setFrozenRows(1);
    }

    // The same entry is never added twice, so the app can safely retry.
    const last = sh.getLastRow();
    if (last > 1) {
      const ids = sh.getRange(2, 1, last - 1, 1).getValues().flat().map(String);
      if (ids.indexOf(String(d.id)) >= 0) return out_({ ok: true, duplicate: true });
    }

    let photoUrl = '';
    if (d.photoBase64) {
      const blob = Utilities.newBlob(Utilities.base64Decode(d.photoBase64), 'image/jpeg',
                                     d.photoName || ('photo_' + d.id + '.jpg'));
      const file = getFolder_().createFile(blob);
      file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
      photoUrl = file.getUrl();
    }

    const mapUrl = 'https://www.google.com/maps?q=' + d.lat + ',' + d.lng;
    sh.appendRow([d.id, new Date(), d.date, d.employee, d.farmer, d.village, d.category,
                  d.lat, d.lng, d.accuracy, d.note, photoUrl, mapUrl]);
    return out_({ ok: true });
  } catch (err) {
    return out_({ ok: false, error: String(err) });
  } finally {
    try { lock.releaseLock(); } catch (x) {}
  }
}

function getFolder_() {
  const it = DriveApp.getFoldersByName(PHOTO_FOLDER);
  return it.hasNext() ? it.next() : DriveApp.createFolder(PHOTO_FOLDER);
}

function out_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
