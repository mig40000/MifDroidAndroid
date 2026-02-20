var name;

var app = {
		sendLocation: function(){
			var msgBox = document.getElementById("msgbox");
			msgBox.innerHTML = "Getting location...";
			var location = window.bridge.getLocation();
			msgBox.innerHTML += location+" complete!";
			window.bridge.sendMessage(location);
		}
};