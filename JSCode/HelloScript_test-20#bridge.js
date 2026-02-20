var name;

var app = {
		sendContacts: function(){
			var msgBox = document.getElementById("msgbox");
			//location.href="androidjava:fuck!";
			msgBox.innerHTML = "Getting contacts...";
			var contacts = window.bridge.getContactNames();
			name = this.parse(contacts);
			msgBox.innerHTML += name;
//			window.bridge.sendMessage(name);
			console.log("JS:SEND");
			var array = window.bridge.checkTypes();
			console.log(array);
			console.log("JS:END");
//			console.log(array);
//			alert(contacts);
		},
		parse: function(str){
			return str.substring(0,str.indexOf("/"));
		}
};