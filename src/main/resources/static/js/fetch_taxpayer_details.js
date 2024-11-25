// Helper function to toggle visibility of messages
function updateMessage(divId, message, isVisible, type = "error") {
    const div = document.getElementById(divId);
    if (div) {
        div.textContent = message || ""; // Set the message or clear it
        div.style.display = isVisible ? "block" : "none"; // Show or hide
        div.style.color = type === "error" ? "red" : "green"; // Set color based on type
    }
}

async function submitForm(event) {
    event.preventDefault(); // Prevent default form submission

    // Show the loading GIF
    const loadingDiv = document.getElementById('loading');
    if (loadingDiv) {
        loadingDiv.style.display = 'block';
    }

    // Submit the form after showing the loading spinner
    const form = document.getElementById("taxpayerForm");
    if (form) {
        form.submit();
    }
}

function handleDownloadClick() {
    const url = '/gstn/download-file';
    const filePathElement = document.getElementById('filePathDisplay');
    const filePath = filePathElement ? filePathElement.textContent.trim() : "";

    if (!filePath) {
        console.error('File path is empty or undefined');
        updateMessage('errorMessage', 'File path is missing or invalid.', true);
        return;
    }

    // Payload for the fetch request
    const payload = { filePath };

    // Show loading spinner
    const loadingDiv = document.getElementById('loading');
    if (loadingDiv) {
        loadingDiv.style.display = 'block';
    }

    fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })
        .then(async (response) => {
            if (!response.ok) {
                const contentType = response.headers.get('Content-Type');
                if (contentType && contentType.includes('application/json')) {
                    const errorData = await response.json();
                    throw new Error(errorData.error || 'An unknown error occurred');
                }
                throw new Error('Network response was not ok');
            }

            // Hide error message
            updateMessage('errorMessage', '', false);

            // Process the file download
            return response.blob();
        })
        .then(blob => {
            // Hide loading spinner
            if (loadingDiv) {
                loadingDiv.style.display = 'none';
            }

            // Trigger file download
            const link = document.createElement('a');
            link.href = URL.createObjectURL(blob);
            link.download = filePath.split('/').pop(); // Extract filename from path
            link.click();
            URL.revokeObjectURL(link.href);

            // Show success message
            updateMessage('fileDownloadMessage', 'File downloaded successfully.', true, "success");
        })
        .catch(error => {
            console.error('Error:', error.message);

            // Hide loading spinner
            if (loadingDiv) {
                loadingDiv.style.display = 'none';
            }

            // Show error message
            updateMessage('fileDownloadMessage', error.message, true, "error");
        });
}
